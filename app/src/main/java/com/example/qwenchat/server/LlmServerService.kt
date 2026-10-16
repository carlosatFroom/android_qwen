package com.example.qwenchat.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.InferenceEngine.State as EngineState
import com.arm.aichat.isModelLoaded
import com.example.qwenchat.MainActivity
import com.example.qwenchat.SettingsKeys
import com.example.qwenchat.settingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Foreground service that hosts the [LlmServer] so the phone can act as a headless inference
 * worker — it keeps running with the screen off, holds wake/wifi locks, and self-loads the
 * model from saved settings so the chat UI never has to be open.
 */
class LlmServerService : Service() {

    companion object {
        private const val TAG = "LlmServerService"
        const val CHANNEL_ID = "llm_server"
        const val NOTIFICATION_ID = 42
        const val DEFAULT_PORT = 11434 // Ollama's default, so existing clients "just work".

        const val ACTION_START = "com.example.qwenchat.server.START"
        const val ACTION_STOP = "com.example.qwenchat.server.STOP"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: InferenceEngine
    private var server: LlmServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var port: Int = DEFAULT_PORT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (isRunning) return START_STICKY

        startInForeground("Starting…")
        acquireLocks()
        isRunning = true

        engine = AiChat.getInferenceEngine(applicationContext)
        scope.launch {
            try {
                val cfg = readConfig()
                port = cfg.port
                ensureModelLoaded(cfg)
                server = LlmServer(
                    engine = engine,
                    modelName = { cfg.modelName },
                    defaultSystemPrompt = { cfg.systemPrompt },
                ).also { it.start(port) }

                updateNotification("Serving on http://${localIp()}:$port")
                Log.i(TAG, "LLM server ready on port $port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start LLM server", e)
                updateNotification("Failed: ${e.message}")
                // Don't linger as a "running" service with nothing listening: isRunning would
                // keep the UI showing "Serving" and make the next start request a no-op.
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Stopping service")
        isRunning = false
        server?.stop()
        server = null
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Model bootstrap ------------------------------------------------------------------

    private data class Config(
        val modelPath: String?,
        val mmprojPath: String?,
        val contextSize: Int,
        val temperature: Float,
        val systemPrompt: String,
        val reasoning: Boolean,
        val port: Int,
    ) {
        val modelName: String get() = modelPath?.let { File(it).nameWithoutExtension } ?: "qwen"
    }

    private suspend fun readConfig(): Config {
        val prefs = settingsStore.data.first()
        return Config(
            modelPath = prefs[stringPreferencesKey(SettingsKeys.MODEL_PATH)],
            mmprojPath = prefs[stringPreferencesKey(SettingsKeys.MMPROJ_PATH)],
            contextSize = prefs[intPreferencesKey(SettingsKeys.CONTEXT_SIZE)] ?: 8192,
            temperature = prefs[floatPreferencesKey(SettingsKeys.TEMPERATURE)] ?: 0.3f,
            systemPrompt = prefs[stringPreferencesKey(SettingsKeys.SYSTEM_PROMPT)] ?: "You are a helpful assistant.",
            reasoning = prefs[booleanPreferencesKey(SettingsKeys.REASONING)] ?: false,
            port = prefs[intPreferencesKey(SettingsKeys.SERVER_PORT)] ?: DEFAULT_PORT,
        )
    }

    /** Load the model if the shared engine isn't already serving one (headless boot path). */
    private suspend fun ensureModelLoaded(cfg: Config) {
        if (engine.state.value.isModelLoaded) {
            Log.i(TAG, "Model already loaded by app; reusing it")
            return
        }
        val path = cfg.modelPath?.takeIf { File(it).exists() }
            ?: throw IllegalStateException("No model configured. Open the app and load a model once.")

        updateNotification("Loading model…")
        engine.state.first { it is EngineState.Initialized || it is EngineState.Error }
        if (engine.state.value is EngineState.Error) throw IllegalStateException("Engine init failed")

        engine.setContextSize(cfg.contextSize)
        engine.loadModel(path)
        engine.setTemperature(cfg.temperature)
        engine.setEnableThinking(cfg.reasoning)
        cfg.mmprojPath?.takeIf { File(it).exists() }?.let { mmproj ->
            runCatching { engine.loadMmproj(mmproj) }
                .onFailure { Log.e(TAG, "mmproj load failed (continuing without vision)", it) }
        }
        engine.setSystemPrompt(cfg.systemPrompt)
    }

    // ---- Locks & notification -------------------------------------------------------------

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QwenChat:LlmServer").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(mode, "QwenChat:LlmServer").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release(); wifiLock = null
    }

    /**
     * Enter the foreground with the correct service type per API level. Android 14+ requires a
     * type; we use SPECIAL_USE there because DATA_SYNC is capped to ~6h/day on Android 15, which
     * would kill an always-on worker. Older devices have no such cap, so DATA_SYNC is fine.
     */
    private fun startInForeground(text: String) {
        val notification = buildNotification(text)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ->
                startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "LLM Server", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LlmServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Qwen LLM server")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    /** Best-effort non-loopback IPv4 address for display in the notification. */
    private fun localIp(): String {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        }.getOrNull() ?: "0.0.0.0"
    }

    /** Helper to start the foreground service correctly per API level. */
    object Controller {
        fun start(context: Context) {
            val intent = Intent(context, LlmServerService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LlmServerService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
