package com.piremote.db

import androidx.room.*

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE url = :serverUrl ORDER BY seq ASC")
    suspend fun getAllByServerUrl(serverUrl: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<ChatMessageEntity>)

    // Wipe persisted history for a server when its session is replaced (/new,
    // /resume), so a later reconnect doesn't re-inject the stale conversation.
    @Query("DELETE FROM chat_messages WHERE url = :serverUrl")
    suspend fun deleteByServerUrl(serverUrl: String)

    /**
     * Atomically replace this server's stored history with [messages]. The host
     * replays full history (with FRESH msgIds) on every reconnect / viewport
     * change, so an insert-only persist doubled the table each cycle and left
     * the restore path interleaving duplicate rows. Delete-then-insert in one
     * transaction keeps exactly one copy.
     */
    @Transaction
    suspend fun replaceAllForUrl(serverUrl: String, messages: List<ChatMessageEntity>) {
        deleteByServerUrl(serverUrl)
        insertAll(messages)
    }
}
