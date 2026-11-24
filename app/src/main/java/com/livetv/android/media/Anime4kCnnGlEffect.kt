package com.livetv.android.media

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.livetv.android.util.VideoEnhanceMode
import com.livetv.android.util.VideoEnhancePolicy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 基于 Anime4K v4 轻量级卷积神经网络（`Restore_CNN` + `Upscale_CNN_x2`）的 Media3 `GlEffect`。
 *
 * - 使用预解析的多 Pass GLES 3.0 着色器清单与 `RGBA16F` 中间特征纹理；
 * - 对 `>= 1080p` 高清源通过 [isNoOp] 自动直通跳过，仅增强 SD / 540p / 720p 直播流。
 */
@UnstableApi
class Anime4kCnnGlEffect(
    private val mode: VideoEnhanceMode,
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        mode == VideoEnhanceMode.OFF || VideoEnhancePolicy.shouldBypassForSource(inputWidth, inputHeight)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        val path2x = VideoEnhancePolicy.shaderAssetPath(mode)
            ?: throw VideoFrameProcessingException("VideoEnhanceMode.OFF cannot create shader program")
        val path4x = VideoEnhancePolicy.shader4xAssetPath(mode)
            ?: throw VideoFrameProcessingException("VideoEnhanceMode.OFF cannot create shader program")
        val passes2x = try {
            context.assets.open(path2x).use { parsePasses(it.readBytes()) }
        } catch (e: Exception) {
            throw VideoFrameProcessingException("Failed to load CNN 2x shader asset: $path2x", e)
        }
        val passes4x = try {
            context.assets.open(path4x).use { parsePasses(it.readBytes()) }
        } catch (e: Exception) {
            throw VideoFrameProcessingException("Failed to load CNN 4x shader asset: $path4x", e)
        }
        return Anime4kCnnShaderProgram(passes2x = passes2x, passes4x = passes4x)
    }

    internal data class ShaderPass(
        val save: String,
        val scale: Int,
        val binds: List<String>,
        val fragSrc: String,
    )

    companion object {
        internal fun parsePasses(raw: ByteArray): List<ShaderPass> {
            var offset = 0
            fun readLine(): String? {
                if (offset >= raw.size) return null
                var end = offset
                while (end < raw.size && raw[end] != '\n'.code.toByte()) {
                    end++
                }
                val line = String(raw, offset, end - offset, Charsets.UTF_8).trimEnd('\r')
                offset = if (end < raw.size) end + 1 else raw.size
                return line
            }

            val firstLine = readLine() ?: error("Empty passes manifest")
            require(firstLine.startsWith("PASS_COUNT ")) { "Invalid header: $firstLine" }
            val count = firstLine.removePrefix("PASS_COUNT ").trim().toInt()
            val passes = ArrayList<ShaderPass>(count)

            repeat(count) {
                var line = readLine()
                while (line != null && line != "PASS_BEGIN") {
                    line = readLine()
                }
                require(line == "PASS_BEGIN") { "Unexpected EOF before PASS_BEGIN" }
                var save = "MAIN"
                var scale = 1
                var binds: List<String> = emptyList()
                val srcLen: Int
                while (true) {
                    val directive = readLine() ?: error("Unexpected EOF in pass header")
                    when {
                        directive.startsWith("SAVE ") -> save = directive.removePrefix("SAVE ").trim()
                        directive.startsWith("SCALE ") -> scale = directive.removePrefix("SCALE ").trim().toInt()
                        directive.startsWith("BINDS ") -> {
                            val rawBinds = directive.removePrefix("BINDS ").trim()
                            binds = if (rawBinds.isEmpty()) emptyList() else rawBinds.split(',')
                        }
                        directive.startsWith("SRC_LEN ") -> {
                            srcLen = directive.removePrefix("SRC_LEN ").trim().toInt()
                            break
                        }
                    }
                }
                require(srcLen > 0 && offset + srcLen <= raw.size) { "Invalid SRC_LEN=$srcLen" }
                val fragSrc = String(raw, offset, srcLen, Charsets.UTF_8)
                offset += srcLen
                passes += ShaderPass(save = save, scale = scale, binds = binds, fragSrc = fragSrc)
            }
            return passes
        }
    }
}

@UnstableApi
private class Anime4kCnnShaderProgram(
    private val passes2x: List<Anime4kCnnGlEffect.ShaderPass>,
    private val passes4x: List<Anime4kCnnGlEffect.ShaderPass>,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ false, /* texturePoolCapacity = */ 1) {

    private data class Target(
        val texId: Int,
        val fboId: Int,
        val width: Int,
        val height: Int,
    )

    private class PassBindLocations(
        val texLoc: Int,
        val sizeLoc: Int,
        val ptLoc: Int,
    )

    private var activePasses: List<Anime4kCnnGlEffect.ShaderPass> = emptyList()
    private var programs: IntArray = IntArray(0)
    private var bindLocations: Array<Array<PassBindLocations>> = emptyArray()
    private val prevFbo = IntArray(1)
    private val prevVao = IntArray(1)
    private val prevViewport = IntArray(4)
    private val targets = LinkedHashMap<String, Target>()
    private var vaoId: Int = 0
    private var inputWidth: Int = 0
    private var inputHeight: Int = 0
    private var outputWidth: Int = 0
    private var outputHeight: Int = 0
    private var currentScale: Int = 0

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val scale = if (VideoEnhancePolicy.shouldUse4xForSource(inputWidth, inputHeight)) 4 else 2
        val desiredPasses = if (scale == 4) passes4x else passes2x

        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        this.outputWidth = inputWidth * scale
        this.outputHeight = inputHeight * scale

        if (currentScale != scale || programs.size != desiredPasses.size) {
            releasePrograms()
            activePasses = desiredPasses
            programs = IntArray(desiredPasses.size)
            ensureVao()
            bindLocations = Array(desiredPasses.size) { i ->
                val prog = linkProgram(VERTEX_SHADER, desiredPasses[i].fragSrc)
                programs[i] = prog
                Array(desiredPasses[i].binds.size) { b ->
                    val bindName = desiredPasses[i].binds[b]
                    PassBindLocations(
                        texLoc = GLES30.glGetUniformLocation(prog, "${bindName}_tex"),
                        sizeLoc = GLES30.glGetUniformLocation(prog, "${bindName}_size"),
                        ptLoc = GLES30.glGetUniformLocation(prog, "${bindName}_pt"),
                    )
                }
            }
            currentScale = scale
        }
        rebuildIntermediateTargets(inputWidth, inputHeight)
        return Size(outputWidth, outputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        if (activePasses.isEmpty() || programs.isEmpty()) return

        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_VERTEX_ARRAY_BINDING, prevVao, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)

        GLES30.glBindVertexArray(vaoId)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        var currentMainTex = inputTexId
        var currentMainW = inputWidth
        var currentMainH = inputHeight

        for (i in activePasses.indices) {
            val pass = activePasses[i]
            val isLast = (i == activePasses.lastIndex)
            if (isLast) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
                GLES30.glViewport(0, 0, outputWidth, outputHeight)
            } else {
                val key = targetKey(i, pass.save)
                val dst = targets[key]
                    ?: throw VideoFrameProcessingException("Missing intermediate target: $key")
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dst.fboId)
                GLES30.glViewport(0, 0, dst.width, dst.height)
            }

            val prog = programs[i]
            GLES30.glUseProgram(prog)
            for (b in pass.binds.indices) {
                val bindName = pass.binds[b]
                val texId: Int
                val bw: Int
                val bh: Int
                if (bindName == "MAIN") {
                    texId = currentMainTex
                    bw = currentMainW
                    bh = currentMainH
                } else {
                    val t = targets[bindName]
                        ?: throw VideoFrameProcessingException("Missing bound target: $bindName")
                    texId = t.texId
                    bw = t.width
                    bh = t.height
                }
                val locs = bindLocations[i][b]
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + b)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
                GLES30.glUniform1i(locs.texLoc, b)
                GLES30.glUniform2f(locs.sizeLoc, bw.toFloat(), bh.toFloat())
                GLES30.glUniform2f(locs.ptLoc, 1.0f / bw.toFloat(), 1.0f / bh.toFloat())
            }
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

            if (!isLast && pass.save == "MAIN") {
                val dst = targets.getValue(targetKey(i, pass.save))
                currentMainTex = dst.texId
                currentMainW = dst.width
                currentMainH = dst.height
            }
        }

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUseProgram(0)
        GLES30.glBindVertexArray(prevVao[0])
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
    }

    override fun release() {
        super.release()
        releaseTargets()
        releasePrograms()
        if (vaoId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
            vaoId = 0
        }
        currentScale = 0
    }

    private fun ensureVao() {
        if (vaoId != 0) return
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vaoId = vaos[0]
    }

    private fun releasePrograms() {
        for (i in programs.indices) {
            if (programs[i] != 0) {
                GLES30.glDeleteProgram(programs[i])
                programs[i] = 0
            }
        }
        programs = IntArray(0)
        activePasses = emptyList()
    }

    private fun rebuildIntermediateTargets(w: Int, h: Int) {
        releaseTargets()
        for (i in 0 until activePasses.lastIndex) {
            val pass = activePasses[i]
            val key = targetKey(i, pass.save)
            if (targets.containsKey(key)) continue
            val tw = w * pass.scale
            val th = h * pass.scale
            val linear = (pass.save == "MAIN")
            targets[key] = createRgba16fTarget(tw, th, linear)
        }
    }

    private fun releaseTargets() {
        for (t in targets.values) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(t.fboId), 0)
            GLES30.glDeleteTextures(1, intArrayOf(t.texId), 0)
        }
        targets.clear()
    }

    private fun targetKey(passIndex: Int, save: String): String =
        if (save == "MAIN") "MAIN_OUT_$passIndex" else save

    private fun createRgba16fTarget(w: Int, h: Int, linear: Boolean): Target {
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        val filter = if (linear) GLES30.GL_LINEAR else GLES30.GL_NEAREST
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA16F,
            w,
            h,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_HALF_FLOAT,
            null,
        )

        val fboIds = IntArray(1)
        GLES30.glGenFramebuffers(1, fboIds, 0)
        val fboId = fboIds[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            texId,
            0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw VideoFrameProcessingException("Incomplete RGBA16F FBO (${w}x$h): status=$status")
        }
        return Target(texId = texId, fboId = fboId, width = w, height = h)
    }

    private fun linkProgram(vertSrc: String, fragSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linked, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (linked[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(prog)
            GLES30.glDeleteProgram(prog)
            throw VideoFrameProcessingException("GL program link failed: $log")
        }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw VideoFrameProcessingException("GL shader compile failed: $log")
        }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER =
            "#version 300 es\n" +
                "out vec2 vTexCoord;\n" +
                "void main() {\n" +
                "  vec2 pos = vec2((gl_VertexID == 1) ? 3.0 : -1.0, (gl_VertexID == 2) ? 3.0 : -1.0);\n" +
                "  vTexCoord = pos * 0.5 + 0.5;\n" +
                "  gl_Position = vec4(pos, 0.0, 1.0);\n" +
                "}\n"
    }
}
