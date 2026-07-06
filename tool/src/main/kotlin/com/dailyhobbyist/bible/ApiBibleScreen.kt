package com.dailyhobbyist.bible

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

sealed interface ApiBibleMode {
    data object Loading : ApiBibleMode
    /** No key yet, or the user wants to change it. */
    data class KeyEntry(val message: String?, val checking: Boolean) : ApiBibleMode
    /** Key valid: show the list of Bibles it unlocks. */
    data class Picker(
        val available: List<ApiBibleBible>,
        val enabledIds: Set<String>,
    ) : ApiBibleMode
}

class ApiBibleViewModel(
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    private val json = Json { ignoreUnknownKeys = true }
    val mode = MutableStateFlow<ApiBibleMode>(ApiBibleMode.Loading)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (mode.value is ApiBibleMode.Loading) start()
    }

    private fun start() {
        viewModelScope.launch {
            val key = dataStore.data.first()[BiblePrefs.APIBIBLE_KEY]
            if (key.isNullOrBlank()) {
                mode.value = ApiBibleMode.KeyEntry(message = null, checking = false)
            } else {
                loadPicker(key)
            }
        }
    }

    fun submitKey(rawKey: String) {
        val key = rawKey.trim()
        if (key.isEmpty()) {
            mode.value = ApiBibleMode.KeyEntry("Please enter your API key.", checking = false)
            return
        }
        viewModelScope.launch {
            mode.value = ApiBibleMode.KeyEntry(message = "Checking key…", checking = true)
            try {
                val bibles = Bible.store.apiBibleList(key)
                dataStore.edit { it[BiblePrefs.APIBIBLE_KEY] = key }
                showPicker(bibles)
            } catch (e: Exception) {
                mode.value = ApiBibleMode.KeyEntry(
                    message = e.message ?: "Could not verify. Check Wi-Fi and try again.",
                    checking = false,
                )
            }
        }
    }

    fun changeKey() {
        mode.value = ApiBibleMode.KeyEntry(message = null, checking = false)
    }

    private fun loadPicker(key: String) {
        viewModelScope.launch {
            mode.value = ApiBibleMode.Loading
            try {
                showPicker(Bible.store.apiBibleList(key))
            } catch (e: Exception) {
                mode.value = ApiBibleMode.KeyEntry(
                    message = e.message ?: "Could not reach API.Bible.",
                    checking = false,
                )
            }
        }
    }

    private suspend fun showPicker(available: List<ApiBibleBible>) {
        val enabled = loadEnabled().map { it.id }.toSet()
        mode.value = ApiBibleMode.Picker(available, enabled)
    }

    fun toggle(bible: ApiBibleBible) {
        viewModelScope.launch {
            val current = loadEnabled().toMutableList()
            val existing = current.firstOrNull { it.id == bible.id }
            if (existing != null) {
                current.remove(existing)
                // If the removed one was selected, fall back to KJV/default
                val selected = dataStore.data.first()[BiblePrefs.SELECTED_VERSION]
                if (selected == BibleVersions.APIBIBLE_PREFIX + bible.id) {
                    dataStore.edit { it[BiblePrefs.SELECTED_VERSION] = BibleVersions.default.id }
                }
            } else {
                current.add(
                    SavedApiBibleVersion(
                        id = bible.id,
                        name = bible.name,
                        abbr = bible.abbreviationLocal.ifBlank { bible.abbreviation },
                    )
                )
            }
            dataStore.edit {
                it[BiblePrefs.APIBIBLE_VERSIONS] = json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(
                        SavedApiBibleVersion.serializer()
                    ),
                    current,
                )
            }
            val m = mode.value
            if (m is ApiBibleMode.Picker) {
                mode.value = m.copy(enabledIds = current.map { v -> v.id }.toSet())
            }
        }
    }

    private suspend fun loadEnabled(): List<SavedApiBibleVersion> {
        val raw = dataStore.data.first()[BiblePrefs.APIBIBLE_VERSIONS] ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/**
 * Manage the user's API.Bible account connection:
 * enter/verify the key, then choose which unlocked translations
 * (NIV, NKJV, NLT, ...) appear in the Versions list.
 */
class ApiBibleScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ApiBibleViewModel>(sealedActivity) {

    override val viewModelClass: Class<ApiBibleViewModel>
        get() = ApiBibleViewModel::class.java

    override fun createViewModel(): ApiBibleViewModel {
        Bible.init(lightContext.filesDir)
        return ApiBibleViewModel(lightContext.dataStore)
    }

    @Composable
    override fun Content() {
        val mode by viewModel.mode.collectAsState()
        val textFieldState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()

        BibleScreenSurface {
            when (val m = mode) {
                is ApiBibleMode.Loading -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LightTopBar(
                            leftButton = LightBarButton.LightIcon(
                                icon = LightIcons.BACK,
                                onClick = { goBack() },
                            ),
                            center = LightTopBarCenter.Text("API.Bible"),
                            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = "Loading…",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                            modifier = Modifier.padding(horizontal = 1.5f.gridUnitsAsDp()),
                        )
                    }
                }

                is ApiBibleMode.KeyEntry -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LightTextInputEditor(
                            title = "API.Bible Key",
                            editorKey = "apibible-key",
                            state = textFieldState,
                            keyboardOptionsFlow = keyboardOptionsFlow,
                            onSubmit = { text ->
                                if (!m.checking) viewModel.submitKey(text.toString())
                            },
                            onBack = { goBack() },
                            submitIcon = LightIcons.ACCEPT,
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
                        )
                        LightText(
                            text = m.message
                                ?: ("Get a free key at scripture.api.bible. " +
                                    "On their site, choose your 3 licensed translations " +
                                    "(e.g. NIV, NKJV) — then enter your key here."),
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(
                                horizontal = 1.5f.gridUnitsAsDp(),
                                vertical = 0.75f.gridUnitsAsDp(),
                            ),
                        )
                    }
                }

                is ApiBibleMode.Picker -> PickerContent(m)
            }
        }
    }

    @Composable
    private fun PickerContent(m: ApiBibleMode.Picker) {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = { goBack() },
                ),
                center = LightTopBarCenter.Text("API.Bible"),
                rightButton = LightBarButton.LightIcon(
                    icon = LightIcons.PENCIL,
                    onClick = { viewModel.changeKey() },
                    contentDescription = "change key",
                ),
                modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
            )
            LightText(
                text = "Tap a translation to show or hide it in your Versions list.",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(
                    horizontal = 1.5f.gridUnitsAsDp(),
                    vertical = 0.4f.gridUnitsAsDp(),
                ),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 1.5f.gridUnitsAsDp()),
            ) {
                items(m.available) { bible ->
                    val enabled = m.enabledIds.contains(bible.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { viewModel.toggle(bible) }
                            .padding(vertical = 0.6f.gridUnitsAsDp()),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            LightText(
                                text = bible.name,
                                variant = LightTextVariant.Copy,
                            )
                            LightText(
                                text = listOf(
                                    bible.abbreviationLocal.ifBlank { bible.abbreviation },
                                    bible.language.name,
                                ).filter { it.isNotBlank() }.joinToString(" · "),
                                variant = LightTextVariant.Detail,
                                lighten = true,
                            )
                        }
                        if (enabled) {
                            LightIcon(
                                icon = LightIcons.ACCEPT,
                                contentDescription = "enabled",
                            )
                        }
                    }
                }
            }
        }
    }
}
