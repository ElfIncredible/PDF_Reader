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
enum class ScrollMode { VERTICAL, PAGED, TEXT }

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
    val liteMode: com.folio.pdf.util.LiteMode = com.folio.pdf.util.LiteMode.AUTO,
    /** Text size multiplier for the Text (reflow) reading mode. */
    val reflowScale: Float = 1f,
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
        val LITE_MODE = stringPreferencesKey("lite_mode")
        val REFLOW_SCALE = androidx.datastore.preferences.core.floatPreferencesKey("reflow_scale")
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
                liteMode = p[Keys.LITE_MODE]
                    ?.let { runCatching { com.folio.pdf.util.LiteMode.valueOf(it) }.getOrNull() }
                    ?: com.folio.pdf.util.LiteMode.AUTO,
                reflowScale = p[Keys.REFLOW_SCALE] ?: 1f,
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

    suspend fun setLiteMode(mode: com.folio.pdf.util.LiteMode) {
        context.dataStore.edit { it[Keys.LITE_MODE] = mode.name }
    }

    suspend fun setReflowScale(scale: Float) {
        context.dataStore.edit { it[Keys.REFLOW_SCALE] = scale }
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
