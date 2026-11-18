package com.livetv.android.ui

import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.livetv.android.domain.model.Channel
import kotlin.math.roundToInt

class ChannelCardPresenter : Presenter() {

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
        card.contentText = channel.description
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        card.badgeImage = null
        card.mainImage = null
    }
}
