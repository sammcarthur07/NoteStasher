package com.samc.replynoteapp

data class DocEntry(
    val docId: String,
    val title: String = "",
    val alias: String = "",
    val statsTop: Boolean = false,
    val statsBottom: Boolean = true,
    val timezone: String = java.util.TimeZone.getDefault().id,
    val autoEnabled: Boolean = false,
    val minutes: Int = 5,
    val lastUsedAt: Long = System.currentTimeMillis()
)

data class HubInstallConfig(val hubTickMinutes: Int = 5)

data class DocConfig(
    val statsTop: Boolean? = null,
    val statsBottom: Boolean? = null,
    val statsAnywhere: Boolean? = null,
    val timezone: String? = null,
    val autoEnabled: Boolean? = null,
    val minutes: Int? = null
)

