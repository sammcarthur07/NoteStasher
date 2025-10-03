package com.samc.replynoteapp.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DraftRepository(private val dao: DraftDao) {
    fun observeDraftText(): Flow<String> = dao.observeDraft().map { it?.text ?: "" }
    fun observeDraftHtml(): Flow<String> = dao.observeDraft().map { it?.html ?: "" }
    fun observeDraftAttachmentsJson(): Flow<String> = dao.observeDraft().map { it?.attachmentsJson ?: "" }

    suspend fun getDraftText(): String = dao.getDraft()?.text ?: ""
    suspend fun getDraftHtml(): String = dao.getDraft()?.html ?: ""
    suspend fun getDraftFormat(): String = dao.getDraft()?.format ?: "plain"
    suspend fun getDraftAttachmentsJson(): String = dao.getDraft()?.attachmentsJson ?: ""

    suspend fun saveDraft(text: String) {
        dao.upsert(Draft(text = text, html = null, format = "plain", attachmentsJson = null))
    }

    suspend fun saveDraftHtml(html: String) {
        dao.upsert(Draft(text = "", html = html, format = "html", attachmentsJson = getDraftAttachmentsJson().ifBlank { null }))
    }

    suspend fun saveDraftAttachmentsJson(json: String) {
        val current = dao.getDraft()
        if (current == null) {
            dao.upsert(Draft(text = "", html = null, format = "html", attachmentsJson = json))
        } else {
            dao.upsert(current.copy(attachmentsJson = json))
        }
    }

    suspend fun clearDraft() {
        dao.clear()
    }
}
