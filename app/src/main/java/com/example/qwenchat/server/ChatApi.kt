package com.example.qwenchat.server

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure parsing/formatting helpers for the OpenAI- and Ollama-compatible HTTP endpoints.
 *
 * No Ktor or engine types here so this stays trivially testable.
 */

/** A normalized chat request distilled from either API's wire format. */
data class ChatInput(
    val system: String,
    val user: String,
    val imageData: ByteArray?,
    val stream: Boolean,
)

object ChatApi {

    /**
     * Parse an OpenAI `/v1/chat/completions` or Ollama `/api/chat` request body.
     *
     * Both use a `messages` array of `{role, content}`. The engine is single-turn (each request
     * resets the KV cache and accepts one user turn), but callers like the ollama_queue dispatcher
     * drive multi-turn *refinement* by resending the whole history — `[user, assistant, user, …]`
     * — on each call. To honor that, we linearize all non-system turns into one prompt so the
     * model still sees its own prior answers. System messages become the system prompt.
     */
    fun parseChat(body: String, streamDefault: Boolean): ChatInput {
        val root = JSONObject(body)
        val messages = root.optJSONArray("messages") ?: JSONArray()

        val systemParts = mutableListOf<String>()
        val turns = mutableListOf<Pair<String, String>>() // (role, text) for non-system turns
        var imageData: ByteArray? = null

        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val role = msg.optString("role")
            val (text, img) = extractContent(msg)
            when (role) {
                "system" -> if (text.isNotBlank()) systemParts += text
                "assistant", "model" -> if (text.isNotBlank()) turns += "assistant" to text
                else -> { // user / tool / anything else → treat as user input
                    if (text.isNotBlank()) turns += "user" to text
                    if (img != null) imageData = img
                }
            }
        }

        return ChatInput(
            system = systemParts.joinToString("\n"),
            user = linearizeTurns(turns),
            imageData = imageData,
            stream = root.optBoolean("stream", streamDefault),
        )
    }

    /**
     * Flatten a multi-turn conversation into a single prompt. With no assistant turns this is just
     * the user text(s) joined; with assistant turns present, prior answers are labelled so the
     * model can refine them — matching how the dispatcher's N-iteration loop accumulates history.
     */
    private fun linearizeTurns(turns: List<Pair<String, String>>): String {
        val hasAssistant = turns.any { it.first == "assistant" }
        if (!hasAssistant) return turns.joinToString("\n\n") { it.second }
        return buildString {
            for ((role, text) in turns) {
                when (role) {
                    "assistant" -> append("[Your previous answer]\n").append(text).append("\n\n")
                    else -> append(text).append("\n\n")
                }
            }
        }.trim()
    }

    /** Parse an Ollama `/api/generate` request (single `prompt` field). */
    fun parseGenerate(body: String): ChatInput {
        val root = JSONObject(body)
        return ChatInput(
            system = root.optString("system"),
            user = root.optString("prompt"),
            imageData = root.optJSONArray("images")?.let { decodeFirstBase64(it) },
            stream = root.optBoolean("stream", true),
        )
    }

    /**
     * Extract text + optional image from a single message. Handles:
     *  - Ollama: `content` is a string, images in a sibling `images` base64 array.
     *  - OpenAI: `content` is a string OR an array of `{type:text|image_url}` parts.
     */
    private fun extractContent(msg: JSONObject): Pair<String, ByteArray?> {
        // Ollama-style sibling images array.
        var image: ByteArray? = msg.optJSONArray("images")?.let { decodeFirstBase64(it) }

        val content = msg.opt("content")
        if (content is JSONArray) {
            val sb = StringBuilder()
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                when (part.optString("type")) {
                    "text" -> sb.append(part.optString("text"))
                    "image_url" -> {
                        val url = part.optJSONObject("image_url")?.optString("url")
                            ?: part.optString("image_url")
                        decodeDataUrl(url)?.let { image = it }
                    }
                }
            }
            return sb.toString() to image
        }
        return (content as? String ?: "") to image
    }

    private fun decodeFirstBase64(arr: JSONArray): ByteArray? {
        if (arr.length() == 0) return null
        return runCatching { Base64.decode(arr.getString(0), Base64.DEFAULT) }.getOrNull()
    }

    private fun decodeDataUrl(url: String?): ByteArray? {
        if (url.isNullOrBlank()) return null
        val comma = url.indexOf(',')
        val b64 = if (url.startsWith("data:") && comma >= 0) url.substring(comma + 1) else url
        return runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
    }

    // ---- Response builders ----------------------------------------------------------------

    fun openAiResponse(id: String, created: Long, model: String, content: String, promptEval: Int, eval: Int): String =
        JSONObject().apply {
            put("id", id)
            put("object", "chat.completion")
            put("created", created)
            put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", content)
                })
                put("finish_reason", "stop")
            }))
            put("usage", JSONObject().apply {
                put("prompt_tokens", promptEval)
                put("completion_tokens", eval)
                put("total_tokens", promptEval + eval)
            })
        }.toString()

    fun openAiChunk(
        id: String, created: Long, model: String, token: String?, finish: Boolean,
        promptEval: Int? = null, eval: Int? = null,
    ): String =
        JSONObject().apply {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("delta", JSONObject().apply {
                    if (token != null) put("content", token)
                    if (token == null && !finish) put("role", "assistant")
                })
                put("finish_reason", if (finish) "stop" else JSONObject.NULL)
            }))
            if (finish && promptEval != null && eval != null) {
                put("usage", JSONObject().apply {
                    put("prompt_tokens", promptEval)
                    put("completion_tokens", eval)
                    put("total_tokens", promptEval + eval)
                })
            }
        }.toString()

    fun ollamaResponse(
        model: String, createdAt: String, content: String, done: Boolean,
        promptEval: Int = 0, eval: Int = 0,
    ): String =
        JSONObject().apply {
            put("model", model)
            put("created_at", createdAt)
            put("message", JSONObject().apply {
                put("role", "assistant")
                put("content", content)
            })
            put("done", done)
            if (done) {
                put("done_reason", "stop")
                put("prompt_eval_count", promptEval)
                put("eval_count", eval)
            }
        }.toString()

    /** Ollama `/api/generate` streams `response` strings rather than a message object. */
    fun ollamaGenerate(
        model: String, createdAt: String, response: String, done: Boolean,
        promptEval: Int = 0, eval: Int = 0,
    ): String =
        JSONObject().apply {
            put("model", model)
            put("created_at", createdAt)
            put("response", response)
            put("done", done)
            if (done) {
                put("done_reason", "stop")
                put("prompt_eval_count", promptEval)
                put("eval_count", eval)
            }
        }.toString()

    fun tags(model: String, createdAt: String): String =
        JSONObject().apply {
            put("models", JSONArray().put(JSONObject().apply {
                put("name", model)
                put("model", model)
                put("modified_at", createdAt)
                put("size", 0)
                put("digest", "")
                put("details", JSONObject().apply {
                    put("family", "qwen")
                    put("format", "gguf")
                    put("parameter_size", "")
                    put("quantization_level", "")
                })
            }))
        }.toString()

    fun models(model: String, created: Long): String =
        JSONObject().apply {
            put("object", "list")
            put("data", JSONArray().put(JSONObject().apply {
                put("id", model)
                put("object", "model")
                put("created", created)
                put("owned_by", "local")
            }))
        }.toString()
}
