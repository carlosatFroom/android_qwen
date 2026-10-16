package com.example.qwenchat.server

import android.util.Log
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Writer
import java.time.Instant
import java.util.UUID

/**
 * Embedded HTTP server exposing the in-process [InferenceEngine] as an OpenAI- and
 * Ollama-compatible API, so an external async job queue can use this phone as a worker.
 *
 * The engine owns a single model / KV cache, so all generation is serialized behind a mutex.
 * Each request resets the context first ([InferenceEngine.resetSession]) so unrelated jobs
 * never bleed into one another. Latency is not a goal — throughput-of-one is the design point.
 */
class LlmServer(
    private val engine: InferenceEngine,
    private val modelName: () -> String,
    private val defaultSystemPrompt: () -> String,
) {
    private companion object {
        const val TAG = "LlmServer"
        // Cap a single response so a runaway generation can't hold the phone forever. Real
        // queue answers run up to ~4.7k tokens (and used to get through because the native
        // stop position double-counted the prompt), so the cap sits well above that; the
        // remaining context still bounds it below.
        const val MAX_PREDICT = 8192
    }

    private val genMutex = Mutex()
    @Volatile
    private var server: EmbeddedServer<*, *>? = null

    fun start(port: Int) {
        if (server != null) return
        Log.i(TAG, "Starting LLM server on 0.0.0.0:$port")
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            configureRoutes()
        }.also { it.start(wait = false) }
    }

    fun stop() {
        Log.i(TAG, "Stopping LLM server")
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
        server = null
    }

    val isRunning: Boolean get() = server != null

    // ---- Generation core ------------------------------------------------------------------

    /** Result of one generation: full text plus ollama-style token counts. */
    data class GenResult(val text: String, val promptEvalCount: Int, val evalCount: Int)

    /**
     * Reset context, feed the prompt, and stream tokens through [onToken]. Returns the full
     * accumulated text plus token counts. Serialized so concurrent HTTP requests queue rather
     * than corrupt the shared KV cache; token counts are read inside the lock so they reflect
     * this request and not a subsequent one.
     */
    private suspend fun generate(input: ChatInput, onToken: suspend (String) -> Unit): GenResult =
        genMutex.withLock {
            check(engine.state.value.isModelLoaded) { "No model loaded" }
            engine.resetSession(input.system.ifBlank { defaultSystemPrompt() })

            val ctx = engine.getActiveContextSize()
            val pos = engine.getContextPosition()
            val predict = (ctx - pos - 32).coerceIn(256, MAX_PREDICT)

            val flow: Flow<String> =
                if (input.imageData != null && engine.isVisionLoaded()) {
                    engine.sendUserPromptWithImage(input.user, input.imageData, predict)
                } else {
                    engine.sendUserPrompt(input.user, predict)
                }

            val sb = StringBuilder()
            flow.collect { token ->
                sb.append(token)
                onToken(token)
            }
            GenResult(sb.toString(), engine.getPromptEvalCount(), engine.getEvalCount())
        }

    // ---- Routing --------------------------------------------------------------------------

    private fun Application.configureRoutes() {
        routing {
            get("/") { call.respondText("Qwen Chat LLM server. Model: ${modelName()}") }
            get("/health") { call.respondText("""{"status":"ok"}""", ContentType.Application.Json) }

            // Model listing.
            get("/api/tags") {
                call.respondText(ChatApi.tags(modelName(), nowIso()), ContentType.Application.Json)
            }
            get("/v1/models") {
                call.respondText(ChatApi.models(modelName(), epoch()), ContentType.Application.Json)
            }

            // Ollama chat.
            post("/api/chat") {
                val input = runCatching { ChatApi.parseChat(call.receiveText(), streamDefault = true) }
                    .getOrElse { return@post call.badRequest(it) }
                if (input.user.isBlank()) return@post call.badRequest(IllegalArgumentException("empty prompt"))
                if (input.stream) ollamaStream(call, input, generateApi = false)
                else ollamaBlocking(call, input, generateApi = false)
            }

            // Ollama generate.
            post("/api/generate") {
                val input = runCatching { ChatApi.parseGenerate(call.receiveText()) }
                    .getOrElse { return@post call.badRequest(it) }
                if (input.user.isBlank()) return@post call.badRequest(IllegalArgumentException("empty prompt"))
                if (input.stream) ollamaStream(call, input, generateApi = true)
                else ollamaBlocking(call, input, generateApi = true)
            }

            // OpenAI chat completions.
            post("/v1/chat/completions") {
                val input = runCatching { ChatApi.parseChat(call.receiveText(), streamDefault = false) }
                    .getOrElse { return@post call.badRequest(it) }
                if (input.user.isBlank()) return@post call.badRequest(IllegalArgumentException("empty prompt"))
                if (input.stream) openAiStream(call, input) else openAiBlocking(call, input)
            }
        }
    }

    // ---- Handlers -------------------------------------------------------------------------

    private suspend fun openAiBlocking(call: io.ktor.server.application.ApplicationCall, input: ChatInput) {
        try {
            val result = generate(input) {}
            call.respondText(
                ChatApi.openAiResponse(chatId(), epoch(), modelName(), result.text, result.promptEvalCount, result.evalCount),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            call.serverError(e)
        }
    }

    private suspend fun openAiStream(call: io.ktor.server.application.ApplicationCall, input: ChatInput) {
        val id = chatId()
        val created = epoch()
        val model = modelName()
        call.respondTextWriter(contentType = ContentType.parse("text/event-stream")) {
            try {
                sse(ChatApi.openAiChunk(id, created, model, token = null, finish = false)) // role chunk
                val result = generate(input) { token -> sse(ChatApi.openAiChunk(id, created, model, token, finish = false)) }
                sse(ChatApi.openAiChunk(id, created, model, token = null, finish = true, promptEval = result.promptEvalCount, eval = result.evalCount))
                write("data: [DONE]\n\n"); flush()
            } catch (e: Exception) {
                Log.e(TAG, "stream error", e)
                sse("""{"error":{"message":${jsonString(e.message)}}}""")
            }
        }
    }

    private suspend fun ollamaBlocking(call: io.ktor.server.application.ApplicationCall, input: ChatInput, generateApi: Boolean) {
        try {
            val r = generate(input) {}
            val body = if (generateApi) ChatApi.ollamaGenerate(modelName(), nowIso(), r.text, done = true, promptEval = r.promptEvalCount, eval = r.evalCount)
            else ChatApi.ollamaResponse(modelName(), nowIso(), r.text, done = true, promptEval = r.promptEvalCount, eval = r.evalCount)
            call.respondText(body, ContentType.Application.Json)
        } catch (e: Exception) {
            call.serverError(e)
        }
    }

    private suspend fun ollamaStream(call: io.ktor.server.application.ApplicationCall, input: ChatInput, generateApi: Boolean) {
        val model = modelName()
        call.respondTextWriter(contentType = ContentType.parse("application/x-ndjson")) {
            try {
                val r = generate(input) { token ->
                    val line = if (generateApi) ChatApi.ollamaGenerate(model, nowIso(), token, done = false)
                    else ChatApi.ollamaResponse(model, nowIso(), token, done = false)
                    ndjson(line)
                }
                val last = if (generateApi) ChatApi.ollamaGenerate(model, nowIso(), "", done = true, promptEval = r.promptEvalCount, eval = r.evalCount)
                else ChatApi.ollamaResponse(model, nowIso(), "", done = true, promptEval = r.promptEvalCount, eval = r.evalCount)
                ndjson(last)
            } catch (e: Exception) {
                Log.e(TAG, "stream error", e)
                ndjson("""{"error":${jsonString(e.message)}}""")
            }
        }
    }

    // ---- Small helpers --------------------------------------------------------------------

    private suspend fun Writer.sse(data: String) { write("data: $data\n\n"); flush() }
    private suspend fun Writer.ndjson(data: String) { write(data); write("\n"); flush() }

    private suspend fun io.ktor.server.application.ApplicationCall.badRequest(e: Throwable) =
        respondText("""{"error":${jsonString(e.message)}}""", ContentType.Application.Json, HttpStatusCode.BadRequest)

    private suspend fun io.ktor.server.application.ApplicationCall.serverError(e: Throwable) {
        Log.e(TAG, "request failed", e)
        val status = if (e is IllegalStateException) HttpStatusCode.ServiceUnavailable else HttpStatusCode.InternalServerError
        respondText("""{"error":${jsonString(e.message)}}""", ContentType.Application.Json, status)
    }

    private fun chatId() = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "")
    private fun epoch() = System.currentTimeMillis() / 1000
    private fun nowIso() = Instant.now().toString()
    private fun jsonString(s: String?) = org.json.JSONObject.quote(s ?: "error")
}
