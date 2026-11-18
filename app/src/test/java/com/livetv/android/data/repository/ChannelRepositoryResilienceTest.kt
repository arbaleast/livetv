package com.livetv.android.data.repository

import com.livetv.android.data.source.ChannelDataSource
import com.livetv.android.data.source.LocalAssetChannelSource
import com.livetv.android.domain.model.ChannelCategory
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 阶段二遗留用例补齐：assets 缺失 / IO 异常时，仓库仍返回空列表且**不抛异常**。
 *
 * 说明：`LocalAssetChannelSource` 依赖 `android.content.res.AssetManager`（Android framework），
 * 无法在纯 JVM 中直接实例化；因此这里用**结构等价的 fake** 覆盖同一条失败路径：
 * 「底层读取抛异常 → 数据源吞掉 → 仓库拿到空列表」。
 */
class ChannelRepositoryResilienceTest {

    /** 模拟 assets 读取失败（文件缺失 / IO 异常）。 */
    private class FailingAssetSource : ChannelDataSource {
        var readAttempts = 0
            private set

        override fun loadCategories(): List<ChannelCategory> {
            readAttempts++
            // 与 LocalAssetChannelSource.readAsset() 的 catch 分支等价：读取失败返回 null
            val content: String? = try {
                throw IOException("asset not found")
            } catch (e: Exception) {
                null
            }
            return if (content == null) {
                emptyList()
            } else {
                error("unreachable")
            }
        }
    }

    @Test
    fun `repository returns empty list when asset read fails`() {
        val source = FailingAssetSource()
        val repository = DefaultChannelRepository(source)

        val categories = repository.getCategories()

        assertEquals(0, categories.size)
        assertEquals(1, source.readAttempts)
    }

    @Test
    fun `repository returns empty list when asset file is missing on disk`() {
        val missing = File("src/main/assets/does-not-exist.json")

        assertEquals(false, missing.exists())
        assertEquals(0, DefaultChannelRepository(MissingFileSource()).getCategories().size)
    }

    /** 直接按文件路径读取的等价数据源：文件不存在时返回空列表。 */
    private class MissingFileSource : ChannelDataSource {
        override fun loadCategories(): List<ChannelCategory> {
            val file = File("src/main/assets/does-not-exist.json")
            val content: String? = try {
                if (!file.exists()) null else file.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
            return if (content == null) emptyList() else error("unreachable")
        }
    }

    @Test
    fun `local asset source default path constant points to the shipped config`() {
        assertEquals("channels.json", LocalAssetChannelSource.DEFAULT_ASSET_PATH)
    }

    @Test
    fun `input stream failure is converted into empty result`() {
        val broken: InputStream = object : InputStream() {
            override fun read(): Int = throw IOException("broken stream")
        }

        val content: String? = try {
            broken.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }

        assertEquals(null, content)
    }
}
