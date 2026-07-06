package com.dailyhobbyist.bible

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
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

sealed interface SearchMode {
    data object Input : SearchMode
    data object Searching : SearchMode
    data class Results(val query: String, val hits: List<BibleStore.SearchHit>) : SearchMode
}

class SearchViewModel(
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    val mode = MutableStateFlow<SearchMode>(SearchMode.Input)

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            mode.value = SearchMode.Searching
            try {
                val prefs = dataStore.data.first()
                val versionId = prefs[BiblePrefs.SELECTED_VERSION]
                    ?: BibleVersions.default.id
                val hits = when {
                    versionId == BibleVersions.ESV_ID -> {
                        val key = prefs[BiblePrefs.ESV_API_KEY] ?: ""
                        Bible.store.esvSearch(key, q)
                    }
                    BibleVersions.isApiBible(versionId) -> {
                        val key = prefs[BiblePrefs.APIBIBLE_KEY] ?: ""
                        Bible.store.apiBibleSearch(key, BibleVersions.apiBibleId(versionId), q)
                    }
                    else -> Bible.store.search(versionId, q)
                }
                mode.value = SearchMode.Results(q, hits)
            } catch (e: Exception) {
                mode.value = SearchMode.Results(q, emptyList())
            }
        }
    }

    fun backToInput() {
        mode.value = SearchMode.Input
    }

    override fun onBackPressed(): Boolean {
        // From results, back returns to the input rather than leaving the screen
        return if (mode.value is SearchMode.Results) {
            backToInput()
            true
        } else {
            false
        }
    }
}

class SearchScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SearchViewModel>(sealedActivity) {

    override val viewModelClass: Class<SearchViewModel>
        get() = SearchViewModel::class.java

    override fun createViewModel(): SearchViewModel {
        Bible.init(lightContext.filesDir)
        return SearchViewModel(lightContext.dataStore)
    }

    @Composable
    override fun Content() {
        val mode by viewModel.mode.collectAsState()
        val textFieldState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()

        BibleScreenSurface {
            when (val m = mode) {
                is SearchMode.Input -> {
                    LightTextInputEditor(
                        title = "Search",
                        editorKey = "bible-search",
                        state = textFieldState,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        onSubmit = { text -> viewModel.search(text.toString()) },
                        onBack = { goBack() },
                        submitIcon = LightIcons.SEARCH,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                is SearchMode.Searching -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LightTopBar(
                            center = LightTopBarCenter.Text("Search"),
                            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = "Searching…",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                            modifier = Modifier.padding(horizontal = 1.5f.gridUnitsAsDp()),
                        )
                    }
                }

                is SearchMode.Results -> ResultsContent(m)
            }
        }
    }

    @Composable
    private fun ResultsContent(m: SearchMode.Results) {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = { viewModel.backToInput() },
                ),
                center = LightTopBarCenter.TwoLineDetail(
                    line1 = "Search",
                    line2 = "${m.hits.size} results",
                ),
                modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
            )

            if (m.hits.isEmpty()) {
                LightText(
                    text = "No results for \"${m.query}\". Try different words.",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(horizontal = 1.5f.gridUnitsAsDp()),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 1.5f.gridUnitsAsDp()),
                ) {
                    items(m.hits) { hit ->
                        val book = BibleBooks.all[hit.bookIndex]
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable {
                                    navigateTo({ sa ->
                                        ReaderScreen(
                                            sa,
                                            bookIndex = hit.bookIndex,
                                            chapter = hit.chapter,
                                            highlightVerse = hit.verse,
                                        )
                                    })
                                }
                                .padding(vertical = 0.6f.gridUnitsAsDp()),
                        ) {
                            LightText(
                                text = "${book.name} ${hit.chapter}:${hit.verse}",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                            )
                            LightText(
                                text = hit.text,
                                variant = LightTextVariant.Copy,
                            )
                        }
                    }
                }
            }
        }
    }
}
