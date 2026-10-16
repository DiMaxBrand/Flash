package com.example.flash.ui.theme

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemeMode { LIGHT, DARK, SYSTEM }

/** How two phones find each other. AUTO = NFC always, plus camera on HyperOS devices. */
enum class HandshakeMode { AUTO, NFC, CAMERA }

class ThemeRepository(private val dataStore: DataStore<Preferences>) {

    companion object {
        private val KEY_THEME   = stringPreferencesKey("theme_mode")
        private val KEY_ONBOARD = booleanPreferencesKey("onboarding_shown")
        private val KEY_LANG    = stringPreferencesKey("language")
        private val KEY_HANDSHAKE = stringPreferencesKey("handshake_mode")
    }

    private val preferences = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    val themeMode: Flow<ThemeMode> = preferences.map { prefs ->
        when (prefs[KEY_THEME]) {
            "LIGHT" -> ThemeMode.LIGHT
            "DARK"  -> ThemeMode.DARK
            else    -> ThemeMode.SYSTEM
        }
    }

    val onboardingShown: Flow<Boolean> = preferences.map { prefs ->
        prefs[KEY_ONBOARD] ?: false
    }

    val language: Flow<String> = preferences.map { prefs ->
        prefs[KEY_LANG] ?: ""
    }

    val handshakeMode: Flow<HandshakeMode> = preferences.map { prefs ->
        when (prefs[KEY_HANDSHAKE]) {
            "NFC"    -> HandshakeMode.NFC
            "CAMERA" -> HandshakeMode.CAMERA
            else     -> HandshakeMode.AUTO
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun setOnboardingShown() {
        dataStore.edit { it[KEY_ONBOARD] = true }
    }

    suspend fun setHandshakeMode(mode: HandshakeMode) {
        dataStore.edit { it[KEY_HANDSHAKE] = mode.name }
    }

    suspend fun setLanguage(lang: String) {
        dataStore.edit { it[KEY_LANG] = lang }
    }
}
