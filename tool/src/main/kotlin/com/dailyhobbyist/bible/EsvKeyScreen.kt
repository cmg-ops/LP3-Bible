package com.dailyhobbyist.bible

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed interface EsvKeyMode {
    data object Input : EsvKeyMode
    data object Checking : EsvKeyMode
    data class Failed(val message: String) : EsvKeyMode
}

class EsvKeyViewModel(
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    val mode = MutableStateFlow<EsvKeyMode>(EsvKeyMode.Input)

    fun submit(key: String, onSaved: () -> Unit) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            mode.value = EsvKeyMode.Failed("Please enter your API key.")
            return
        }
        viewModelScope.launch {
            mode.value = EsvKeyMode.Checking
            try {
                Bible.store.validateEsvKey(trimmed)
                dataStore.edit {
                    it[BiblePrefs.ESV_API_KEY] = trimmed
                    it[BiblePrefs.SELECTED_VERSION] = BibleVersions.ESV_ID
                }
                onSaved()
            } catch (e: Exception) {
                mode.value = EsvKeyMode.Failed(
                    e.message ?: "Could not verify the key. Check Wi-Fi and try again."
                )
            }
        }
    }
}

/**
 * Enter and verify a Crossway ESV API key.
 * Get a free key for personal use at api.esv.org.
 */
class EsvKeyScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, EsvKeyViewModel>(sealedActivity) {

    override val viewModelClass: Class<EsvKeyViewModel>
        get() = EsvKeyViewModel::class.java

    override fun createViewModel(): EsvKeyViewModel {
        Bible.init(lightContext.filesDir)
        return EsvKeyViewModel(lightContext.dataStore)
    }

    @Composable
    override fun Content() {
        val mode by viewModel.mode.collectAsState()
        val textFieldState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()

        BibleScreenSurface {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTextInputEditor(
                    title = "ESV API Key",
                    editorKey = "esv-key",
                    state = textFieldState,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    onSubmit = { text ->
                        viewModel.submit(text.toString()) { goBack() }
                    },
                    onBack = { goBack() },
                    submitIcon = LightIcons.ACCEPT,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                )

                val footer = when (val m = mode) {
                    is EsvKeyMode.Input ->
                        "Get a free key at api.esv.org, then type it here. " +
                            "ESV is fetched online as you read."
                    is EsvKeyMode.Checking -> "Checking key…"
                    is EsvKeyMode.Failed -> m.message
                }
                LightText(
                    text = footer,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(
                        horizontal = 1.5f.gridUnitsAsDp(),
                        vertical = 0.75f.gridUnitsAsDp(),
                    ),
                )
            }
        }
    }
}
