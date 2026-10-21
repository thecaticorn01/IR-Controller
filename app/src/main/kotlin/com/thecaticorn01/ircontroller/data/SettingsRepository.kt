package com.thecaticorn01.ircontroller.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ThemeMode(val displayName: String) { SYSTEM("System default"), LIGHT("Light"), DARK("Dark") }

enum class RemoteDisplayMode { KEYPAD, LIST }

data class AppSettings(
    /** Extra frames appended to every press, for devices that need a code repeated. */
    val extraRepeats: Int = 0,
    /** Arrow keys, Enter, Space, +/−, M, P and digits press buttons on a hardware keyboard. */
    val keyboardShortcuts: Boolean = true,
    /** The phone's volume keys change the remote's volume while a remote is open. */
    val volumeKeys: Boolean = false,
    val haptics: Boolean = true,
    val learnTimeoutSeconds: Int = 20,
    /** Carrier frequency recorded for learned codes (the receiver doesn't measure it). */
    val learnFrequency: Int = 38_000,
    /** Store recognised learned codes as clean protocol codes instead of raw recordings. */
    val preferDecoded: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Accept PRESS_BUTTON broadcasts from automation apps. */
    val allowAutomation: Boolean = false,
    /** Remote shown by the home-screen widget; the first favourite when unset. */
    val widgetRemoteId: String? = null,
    val remoteDisplayMode: RemoteDisplayMode = RemoteDisplayMode.KEYPAD,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

/** User preferences, persisted with DataStore. */
class SettingsRepository(private val context: Context, scope: CoroutineScope) {

    private object Keys {
        val extraRepeats = intPreferencesKey("extraRepeats")
        val keyboardShortcuts = booleanPreferencesKey("keyboardShortcuts")
        val volumeKeys = booleanPreferencesKey("volumeKeys")
        val haptics = booleanPreferencesKey("haptics")
        val learnTimeout = intPreferencesKey("learnTimeout")
        val learnFrequency = intPreferencesKey("learnFrequency")
        val preferDecoded = booleanPreferencesKey("preferDecodedProtocol")
        val themeMode = stringPreferencesKey("themeMode")
        val dynamicColor = booleanPreferencesKey("dynamicColor")
        val allowAutomation = booleanPreferencesKey("allowAutomation")
        val widgetRemoteId = stringPreferencesKey("widgetRemoteId")
        val remoteDisplayMode = stringPreferencesKey("remoteDisplayMode")
    }

    private val defaults = AppSettings()

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            extraRepeats = p[Keys.extraRepeats] ?: defaults.extraRepeats,
            keyboardShortcuts = p[Keys.keyboardShortcuts] ?: defaults.keyboardShortcuts,
            volumeKeys = p[Keys.volumeKeys] ?: defaults.volumeKeys,
            haptics = p[Keys.haptics] ?: defaults.haptics,
            learnTimeoutSeconds = p[Keys.learnTimeout] ?: defaults.learnTimeoutSeconds,
            learnFrequency = p[Keys.learnFrequency] ?: defaults.learnFrequency,
            preferDecoded = p[Keys.preferDecoded] ?: defaults.preferDecoded,
            themeMode = p[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: defaults.themeMode,
            dynamicColor = p[Keys.dynamicColor] ?: defaults.dynamicColor,
            allowAutomation = p[Keys.allowAutomation] ?: defaults.allowAutomation,
            widgetRemoteId = p[Keys.widgetRemoteId],
            remoteDisplayMode = p[Keys.remoteDisplayMode]?.let { runCatching { RemoteDisplayMode.valueOf(it) }.getOrNull() }
                ?: defaults.remoteDisplayMode,
        )
    }

    val settings: StateFlow<AppSettings> = flow.stateIn(scope, SharingStarted.Eagerly, defaults)

    /** The stored settings, waiting for the first read from disk if necessary. */
    suspend fun current(): AppSettings = flow.first()

    private val writeScope = scope

    private fun <T> set(key: Preferences.Key<T>, value: T?) {
        writeScope.launch {
            context.dataStore.edit { if (value == null) it.remove(key) else it[key] = value }
        }
    }

    fun setExtraRepeats(value: Int) = set(Keys.extraRepeats, value.coerceIn(0, 10))
    fun setKeyboardShortcuts(value: Boolean) = set(Keys.keyboardShortcuts, value)
    fun setVolumeKeys(value: Boolean) = set(Keys.volumeKeys, value)
    fun setHaptics(value: Boolean) = set(Keys.haptics, value)
    fun setLearnTimeout(seconds: Int) = set(Keys.learnTimeout, seconds.coerceIn(5, 60))
    fun setLearnFrequency(hertz: Int) = set(Keys.learnFrequency, hertz)
    fun setPreferDecoded(value: Boolean) = set(Keys.preferDecoded, value)
    fun setThemeMode(value: ThemeMode) = set(Keys.themeMode, value.name)
    fun setDynamicColor(value: Boolean) = set(Keys.dynamicColor, value)
    fun setAllowAutomation(value: Boolean) = set(Keys.allowAutomation, value)
    fun setWidgetRemoteId(value: String?) = set(Keys.widgetRemoteId, value)
    fun setRemoteDisplayMode(value: RemoteDisplayMode) = set(Keys.remoteDisplayMode, value.name)
}
