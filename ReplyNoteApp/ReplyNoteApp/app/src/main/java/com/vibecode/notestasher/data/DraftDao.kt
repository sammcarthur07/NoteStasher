package com.vibecode.notestasher.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {
    @Query("SELECT * FROM draft WHERE id = ${Draft.SINGLETON_ID} LIMIT 1")
    fun observeDraft(): Flow<Draft?>

    @Query("SELECT * FROM draft WHERE id = ${Draft.SINGLETON_ID} LIMIT 1")
    suspend fun getDraft(): Draft?

    @Upsert
    suspend fun upsert(draft: Draft)

    @Query("DELETE FROM draft")
    suspend fun clear()
}

