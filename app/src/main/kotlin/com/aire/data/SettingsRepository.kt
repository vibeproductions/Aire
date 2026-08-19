package com.aire.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Manages user settings for Aire, including AI configuration and appearance.
 * 
 * SENSITIVE DATA (API keys): Stored using [EncryptedSharedPreferences] which utilizes
 * the Android Keystore system for hardware-backed encryption. 
 * Note: Encrypted keys are NOT transferable via standard Android backups as the
 * encryption keys are unique to the physical hardware.
 * 
 * GENERAL DATA: Stored using [Preference DataStore].
 */
class SettingsRepository(private val context: Context) {

    private val KEY_MODEL = stringPreferencesKey("ai_model")
    private val KEY_APPEARANCE = stringPreferencesKey("appearance")
    private val KEY_LOCATION_ENABLED = booleanPreferencesKey("location_enabled")
    private val KEY_STORE_LOCATION = booleanPreferencesKey("store_location")
    private val KEY_SHARE_LOCATION_AI = booleanPreferencesKey("share_location_ai")

    // --- Encrypted Storage for Secrets ---
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs = EncryptedSharedPreferences.create(
        context,
        "secure_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // Flow for Anthropic API Key
    val anthropicApiKey: Flow<String?> = callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "anthropic_api_key") {
                trySend(prefs.getString(key, null))
            }
        }
        securePrefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(securePrefs.getString("anthropic_api_key", null))
        awaitClose { securePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.onStart { emit(securePrefs.getString("anthropic_api_key", null)) }

    // Flow for Google API Key
    val googleApiKey: Flow<String?> = callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "google_api_key") {
                trySend(prefs.getString(key, null))
            }
        }
        securePrefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(securePrefs.getString("google_api_key", null))
        awaitClose { securePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.onStart { emit(securePrefs.getString("google_api_key", null)) }

    // --- DataStore for General Settings ---
    val aiModel: Flow<String> = context.dataStore.data.map { it[KEY_MODEL] ?: "claude-haiku-4-5" }
    val appearance: Flow<String> = context.dataStore.data.map { it[KEY_APPEARANCE] ?: "System" }
    val locationFeaturesEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_LOCATION_ENABLED] ?: false }
    val storeLocationWithMemories: Flow<Boolean> = context.dataStore.data.map { it[KEY_STORE_LOCATION] ?: false }
    val shareLocationWithAi: Flow<Boolean> = context.dataStore.data.map { it[KEY_SHARE_LOCATION_AI] ?: false }

    suspend fun setApiKey(key: String) {
        securePrefs.edit { putString("anthropic_api_key", key.ifBlank { null }) }
    }

    suspend fun setGoogleApiKey(key: String) {
        securePrefs.edit { putString("google_api_key", key.ifBlank { null }) }
    }

    suspend fun setModel(model: String) {
        context.dataStore.edit { it[KEY_MODEL] = model }
    }

    suspend fun setAppearance(appearance: String) {
        context.dataStore.edit { it[KEY_APPEARANCE] = appearance }
    }

    suspend fun setLocationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_LOCATION_ENABLED] = enabled }
    }

    suspend fun setStoreLocation(enabled: Boolean) {
        context.dataStore.edit { it[KEY_STORE_LOCATION] = enabled }
    }

    suspend fun setShareLocationAi(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SHARE_LOCATION_AI] = enabled }
    }
}
