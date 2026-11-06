package com.folio.pdf.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How pages flow in the reader. */
enum class ScrollMode { VERTICAL, PAGED }

/** Paper colour while reading. */
enum class ColorMode { NORMAL, SEPIA, NIGHT }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val keepScreenOn: Boolean = false,
    val scrollMode: ScrollMode = ScrollMode.VERTICAL,
    val colorMode: ColorMode = ColorMode.NORMAL,
    /** Folio server address, e.g. http://192.168.1.20:8000 (blank = not set up). */
    val serverUrl: String = "",
    val serverToken: String = "",
    /** The user agreed that document text/files may be sent to their server. */
    val serverConsent: Boolean = false,
    /** Folder the user granted for the Browse tab (a SAF tree URI). */
    val libraryTreeUri: String? = null,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val LIBRARY_TREE = stringPreferencesKey("library_tree_uri")
        val SCROLL_MODE = stringPreferencesKey("scroll_mode")
        val COLOR_MODE = stringPreferencesKey("color_mode")
        val SERVER_URL = stringPreferencesKey("server_url")
        val SERVER_TOKEN = stringPreferencesKey("server_token")
        val SERVER_CONSENT = booleanPreferencesKey("server_consent")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            AppSettings(
                themeMode = p[Keys.THEME_MODE]
                    ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                dynamicColor = p[Keys.DYNAMIC_COLOR] ?: true,
                keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: false,
                libraryTreeUri = p[Keys.LIBRARY_TREE],
                serverUrl = p[Keys.SERVER_URL].orEmpty(),
                serverToken = p[Keys.SERVER_TOKEN].orEmpty(),
                serverConsent = p[Keys.SERVER_CONSENT] ?: false,
                scrollMode = p[Keys.SCROLL_MODE]
                    ?.let { runCatching { ScrollMode.valueOf(it) }.getOrNull() }
                    ?: ScrollMode.VERTICAL,
                colorMode = p[Keys.COLOR_MODE]
                    ?.let { runCatching { ColorMode.valueOf(it) }.getOrNull() }
                    ?: ColorMode.NORMAL,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setScrollMode(mode: ScrollMode) {
        context.dataStore.edit { it[Keys.SCROLL_MODE] = mode.name }
    }

    suspend fun setColorMode(mode: ColorMode) {
        context.dataStore.edit { it[Keys.COLOR_MODE] = mode.name }
    }

    suspend fun setServer(url: String, token: String) {
        context.dataStore.edit {
            it[Keys.SERVER_URL] = url.trim().trimEnd('/')
            it[Keys.SERVER_TOKEN] = token.trim()
        }
    }

    suspend fun setServerConsent(allowed: Boolean) {
        context.dataStore.edit { it[Keys.SERVER_CONSENT] = allowed }
    }

    suspend fun setLibraryTreeUri(uri: String?) {
        context.dataStore.edit {
            if (uri == null) it.remove(Keys.LIBRARY_TREE) else it[Keys.LIBRARY_TREE] = uri
        }
    }
}
