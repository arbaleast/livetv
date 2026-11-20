package com.livetv.android.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.livetv.android.R
import com.livetv.android.domain.model.Channel
import com.livetv.android.ui.mobile.MobileChannelList
import kotlin.math.roundToInt

/**
 * TV 端频道卡片 Presenter（Leanback）。
 *
 * 遵循 `docs/TV-INTERACTION.md` 第 3 节规范：
 * - 不缓存 Context，仅在 `onCreateViewHolder` 使用 `parent.context`；
 * - 使用轻量级自绘 Drawable 呈现频道简标与分类强调色，消除空白主图占位；
 * - `onUnbindViewHolder` 清理 `mainImage` 与 `badgeImage` 引用。
 */
class ChannelCardPresenter(
    private val categoryName: String = "",
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val density = parent.resources.displayMetrics.density
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            val width = (313 * density).roundToInt()
            val height = (176 * density).roundToInt()
            setMainImageDimensions(width, height)
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val channel = item as Channel
        val card = viewHolder.view as ImageCardView
        card.titleText = channel.name
        card.contentText = MobileChannelList.sanitizeSubtitle(channel).orEmpty()

        val accent = resolveAccentColor(card, categoryName)
        val badgeText = MobileChannelList.extractChannelBadge(channel.name)
        card.mainImage = ChannelCardDrawable(
            badgeText = badgeText,
            categoryLabel = categoryName,
            accentColor = accent,
            density = card.resources.displayMetrics.density,
        )
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        card.badgeImage = null
        card.mainImage = null
    }

    private fun resolveAccentColor(card: ImageCardView, category: String): Int {
        val typed = card.resources.obtainTypedArray(R.array.mobile_badge_accents)
        try {
            if (typed.length() == 0) return DEFAULT_ACCENT
            val index = MobileChannelList.categoryAccentIndex(category)
            return typed.getColor(index % typed.length(), DEFAULT_ACCENT)
        } finally {
            typed.recycle()
        }
    }

    /**
     * 轻量级频道主图占位绘制：深色卡片底 + 分类强调色圆角徽章 + 居中频道简标。
     * 不分配任何 Bitmap，解绑置 null 即可回收。
     */
    private class ChannelCardDrawable(
        private val badgeText: String,
        private val categoryLabel: String,
        private val accentColor: Int,
        private val density: Float,
    ) : Drawable() {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BG_COLOR
            style = Paint.Style.FILL
        }
        private val pillFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(accentColor, 0.18f)
            style = Paint.Style.FILL
        }
        private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(accentColor, 0.65f)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = 26f * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val categoryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SUBTITLE_COLOR
            textSize = 11f * density
            textAlign = Paint.Align.LEFT
        }
        private val badgeRect = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawRect(b, bgPaint)

            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val halfW = 46f * density
            val halfH = 28f * density
            badgeRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
            val radius = 14f * density
            canvas.drawRoundRect(badgeRect, radius, radius, pillFillPaint)
            canvas.drawRoundRect(badgeRect, radius, radius, pillStrokePaint)

            val fm = badgeTextPaint.fontMetrics
            val textY = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(badgeText, cx, textY, badgeTextPaint)

            if (categoryLabel.isNotBlank()) {
                canvas.drawText(categoryLabel, b.left + 14f * density, b.top + 22f * density, categoryTextPaint)
            }
        }

        override fun setAlpha(alpha: Int) {
            bgPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bgPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    companion object {
        private const val BG_COLOR = 0xFF151A23.toInt()
        private const val SUBTITLE_COLOR = 0xFFB0BACB.toInt()
        private const val DEFAULT_ACCENT = 0xFF3B82F6.toInt()

        private fun withAlpha(color: Int, factor: Float): Int = Color.argb(
            (Color.alpha(color) * factor).toInt().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
    }
}
