package com.livetv.android.model

data class Channel(
    val id: String,
    val name: String,
    val description: String,
    val streamUrl: String = "",
)
