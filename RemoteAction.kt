package com.save.me

data class RemoteAction(
    val type: String,
    val status: String, // pending, success, error
    val timestamp: Long,
    val preview: String? = null,
    val error: String? = null
)