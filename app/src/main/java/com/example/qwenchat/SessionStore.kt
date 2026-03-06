package com.example.qwenchat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Session(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val messages: List<MainViewModel.ChatMessage> = emptyList(),
)

class SessionStore(context: Context) {

    private val sessionsDir = File(context.filesDir, "sessions").also { it.mkdirs() }

    suspend fun saveSession(session: Session) = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("id", session.id)
            put("name", session.name)
            put("createdAt", session.createdAt)
            put("messages", JSONArray().apply {
                session.messages.forEach { msg ->
                    put(JSONObject().apply {
                        put("id", msg.id)
                        put("content", msg.content)
                        put("isUser", msg.isUser)
                    })
                }
            })
        }
        File(sessionsDir, "${session.id}.json").writeText(json.toString())
    }

    suspend fun loadSession(id: String): Session? = withContext(Dispatchers.IO) {
        val file = File(sessionsDir, "$id.json")
        if (!file.exists()) return@withContext null
        parseSession(file.readText())
    }

    suspend fun listSessions(): List<Session> = withContext(Dispatchers.IO) {
        sessionsDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    parseSession(file.readText())
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        File(sessionsDir, "$id.json").delete()
    }

    private fun parseSession(text: String): Session {
        val json = JSONObject(text)
        val msgs = json.getJSONArray("messages")
        val messages = (0 until msgs.length()).map { i ->
            val m = msgs.getJSONObject(i)
            MainViewModel.ChatMessage(
                id = m.getLong("id"),
                content = m.getString("content"),
                isUser = m.getBoolean("isUser"),
            )
        }
        return Session(
            id = json.getString("id"),
            name = json.optString("name", ""),
            createdAt = json.getLong("createdAt"),
            messages = messages,
        )
    }
}
