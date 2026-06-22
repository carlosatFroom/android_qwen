package com.example.qwenchat

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Single process-wide DataStore for app settings.
 *
 * DataStore requires exactly one instance per file per process, so both [MainViewModel] and
 * the headless server service must read/write through this same delegate. The preference key
 * *names* below are the source of truth shared across both.
 */
val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Preference key names. Preferences keys compare by name, so reusing these strings is enough. */
object SettingsKeys {
    const val TEMPERATURE = "temperature"
    const val CONTEXT_SIZE = "context_size"
    const val SYSTEM_PROMPT = "system_prompt"
    const val REASONING = "reasoning"
    const val MODEL_PATH = "model_path"
    const val MMPROJ_PATH = "mmproj_path"
    const val RECURSION_DEPTH = "recursion_depth"

    /** Server-specific. */
    const val SERVER_PORT = "server_port"
    const val SERVER_AUTOSTART = "server_autostart"
}
