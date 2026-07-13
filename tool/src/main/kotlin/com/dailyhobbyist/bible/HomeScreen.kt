package com.dailyhobbyist.bible

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

sealed interface HomeMode {
    data object Loading : HomeMode
    data class FirstRun(val progress: Float?, val error: String?) : HomeMode
    data class Menu(val versionName: String) : HomeMode
}

class HomeViewModel(
    private val dataStore: DataStore<Preferences>,
    private val repository: BibleRepository,
) : LightViewModel<Unit>() {

    val mode = MutableStateFlow<HomeMode>(HomeMode.Loading)
    val toast = MutableStateFlow<String?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        toast.value = null   // clear any stale "no saved spot" hint on return
        refresh()
    }

    private val jsonCodec = Json { ignoreUnknownKeys = true }

    fun refresh() {
        viewModelScope.launch {
            val prefs = dataStore.data.first()
            val selectedId = prefs[BiblePrefs.SELECTED_VERSION]
            val esvKeySet = !prefs[BiblePrefs.ESV_API_KEY].isNullOrBlank()
            val abKeySet = !prefs[BiblePrefs.APIBIBLE_KEY].isNullOrBlank()
            val downloaded = Bible.store.downloadedVersionIds()

            val savedJson = prefs[BiblePrefs.APIBIBLE_VERSIONS]
            val abVersions: List<SavedApiBibleVersion> = if (savedJson.isNullOrBlank()) {
                emptyList()
            } else {
                try {
                    jsonCodec.decodeFromString(savedJson)
                } catch (e: Exception) {
                    emptyList()
                }
            }

            val selectedIsValid = selectedId != null && (
                downloaded.contains(selectedId) ||
                    (selectedId == BibleVersions.ESV_ID && esvKeySet) ||
                    (BibleVersions.isApiBible(selectedId) && abKeySet &&
                        abVersions.any { it.id == BibleVersions.apiBibleId(selectedId) })
            )

            if (downloaded.isEmpty() && !esvKeySet && abVersions.isEmpty()) {
                mode.value = HomeMode.FirstRun(progress = null, error = null)
                return@launch
            }

            val effectiveId = if (selectedIsValid) {
                selectedId!!
            } else {
                val fallback = downloaded.firstOrNull() ?: BibleVersions.ESV_ID
                dataStore.edit { it[BiblePrefs.SELECTED_VERSION] = fallback }
                fallback
            }

            val name = if (BibleVersions.isApiBible(effectiveId)) {
                abVersions.firstOrNull { it.id == BibleVersions.apiBibleId(effectiveId) }?.name
                    ?: "API.Bible"
            } else {
                (BibleVersions.byId(effectiveId) ?: BibleVersions.default).name
            }
            mode.value = HomeMode.Menu(name)
        }
    }

    /**
     * Loads the saved position for the current version and invokes [open]
     * with (bookIndex, chapter, verse). If nothing is saved yet, shows a hint.
     */
    fun resumeReading(open: (Int, Int, Int) -> Unit) {
        viewModelScope.launch {
            val versionId = dataStore.data.first()[BiblePrefs.SELECTED_VERSION]
                ?: BibleVersions.default.id
            val pos = withContext(Dispatchers.IO) { repository.getPosition(versionId) }
            if (pos == null) {
                toast.value = "No bookmark yet. Tap the bookmark icon while reading to set one."
            } else {
                open(pos.bookIndex, pos.chapter, pos.verse)
            }
        }
    }

    fun clearToast() {
        toast.value = null
    }

    fun startFirstRunDownload() {
        viewModelScope.launch {
            mode.value = HomeMode.FirstRun(progress = 0f, error = null)
            try {
                Bible.store.download(BibleVersions.default) { p ->
                    mode.value = HomeMode.FirstRun(progress = p, error = null)
                }
                dataStore.edit { it[BiblePrefs.SELECTED_VERSION] = BibleVersions.default.id }
                refresh()
            } catch (e: Exception) {
                mode.value = HomeMode.FirstRun(
                    progress = null,
                    error = e.message ?: "Download failed. Check Wi-Fi and try again.",
                )
            }
        }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel(): HomeViewModel {
        Bible.init(lightContext.filesDir)
        return HomeViewModel(lightContext.dataStore, lightContext.bibleRepository())
    }

    @Composable
    override fun Content() {
        val mode by viewModel.mode.collectAsState()
        val toast by viewModel.toast.collectAsState()

        // The top bar title is the version being read (e.g. "King James Version").
        // Before anything is downloaded there's no version yet, so fall back to "Bible".
        val title = when (val m = mode) {
            is HomeMode.Menu -> m.versionName
            else -> "Bible"
        }

        BibleScreenSurface {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    center = LightTopBarCenter.Text(title),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                when (val m = mode) {
                    is HomeMode.Loading -> {
                        LightText(
                            text = "Loading…",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                            modifier = Modifier.padding(horizontal = 1.5f.gridUnitsAsDp()),
                        )
                    }

                    is HomeMode.FirstRun -> FirstRunContent(m)

                    is HomeMode.Menu -> MenuContent()
                }

                toast?.let { msg ->
                    LightText(
                        text = msg,
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

    @Composable
    private fun FirstRunContent(m: HomeMode.FirstRun) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1.5f.gridUnitsAsDp()),
        ) {
            Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))
            LightText(
                text = "Welcome",
                variant = LightTextVariant.Heading,
            )
            Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
            LightText(
                text = "The Bible needs a one-time download of the King James Version (about 8 MB). After that, everything works offline.",
                variant = LightTextVariant.Copy,
                lighten = true,
            )
            Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))

            when {
                m.progress != null -> {
                    val pct = (m.progress * 100).toInt()
                    LightText(
                        text = if (m.progress < 0.7f) "Downloading… $pct%" else "Installing… $pct%",
                        variant = LightTextVariant.Copy,
                    )
                }
                m.error != null -> {
                    LightText(
                        text = m.error,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                    Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                    LightText(
                        text = "TRY AGAIN",
                        variant = LightTextVariant.Button,
                        modifier = Modifier
                            .lightClickable { viewModel.startFirstRunDownload() }
                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                    )
                }
                else -> {
                    LightText(
                        text = "DOWNLOAD KJV",
                        variant = LightTextVariant.Button,
                        modifier = Modifier
                            .lightClickable { viewModel.startFirstRunDownload() }
                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }

    @Composable
    private fun MenuContent() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1.5f.gridUnitsAsDp()),
        ) {
            Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))

            MenuRow("OLD TESTAMENT") {
                navigateTo({ sa -> BooksScreen(sa, isOldTestament = true) })
            }
            MenuRow("NEW TESTAMENT") {
                navigateTo({ sa -> BooksScreen(sa, isOldTestament = false) })
            }
            MenuRow("BOOKMARK") {
                viewModel.resumeReading { book, chapter, verse ->
                    navigateTo({ sa -> ReaderScreen(sa, book, chapter, verse) })
                }
            }
            MenuRow("SAVED") {
                navigateTo(::SavedScreen)
            }
            MenuRow("SEARCH") {
                navigateTo(::SearchScreen)
            }
            MenuRow("SETTINGS") {
                navigateTo(::VersionsScreen)
            }
        }
    }

    @Composable
    private fun MenuRow(label: String, onClick: () -> Unit) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable { onClick() }
                .padding(vertical = 0.9f.gridUnitsAsDp()),
        )
    }
}
