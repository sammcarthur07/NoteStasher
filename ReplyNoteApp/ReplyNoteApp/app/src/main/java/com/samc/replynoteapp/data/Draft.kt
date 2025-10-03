package com.samc.replynoteapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "draft")
data class Draft(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val text: String = "",
    val html: String? = null,
    val format: String = "plain", // "plain" or "html"
    val attachmentsJson: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
