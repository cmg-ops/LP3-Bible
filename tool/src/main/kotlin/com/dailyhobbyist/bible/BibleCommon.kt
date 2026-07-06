package com.dailyhobbyist.bible

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

/** DataStore keys */
object BiblePrefs {
    val SELECTED_VERSION = stringPreferencesKey("selected_version")
    val ESV_API_KEY = stringPreferencesKey("esv_api_key")
    val APIBIBLE_KEY = stringPreferencesKey("apibible_key")
    /** JSON list of the API.Bible versions the user has enabled. */
    val APIBIBLE_VERSIONS = stringPreferencesKey("apibible_versions")
}

/**
 * Standard black-background themed wrapper every screen uses,
 * so each screen body only worries about its own content.
 */
@Composable
fun BibleScreenSurface(content: @Composable () -> Unit) {
    val themeColors by LightThemeController.colors.collectAsState()
    LightTheme(colors = themeColors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            content()
        }
    }
}
