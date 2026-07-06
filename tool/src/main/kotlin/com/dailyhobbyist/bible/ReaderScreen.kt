package com.dailyhobbyist.bible

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ReaderState(
    val bookIndex: Int,
    val chapter: Int,
    val versionId: String = "",
    val verses: List<VerseJson> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** Verse numbers the reader has tapped — session only, never saved. */
    val highlighted: Set<Int> = emptySet(),
    /** One-time target verse to scroll to (from Lookup or Search). */
    val scrollTarget: Int? = null,
    /** Publisher copyright line for online versions (shown after the last verse). */
    val copyright: String? = null,
)

class ReaderViewModel(
    private val dataStore: DataStore<Preferences>,
    initialBook: Int,
    initialChapter: Int,
    initialTarget: Int?,
) : LightViewModel<Unit>() {

    val state = MutableStateFlow(
        ReaderState(bookIndex = initialBook, chapter = initialChapter, scrollTarget = initialTarget)
    )

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (state.value.verses.isEmpty()) load()
    }

    private fun load() {
        viewModelScope.launch {
            val s = state.value
            state.value = s.copy(loading = true, error = null)
            try {
                val prefs = dataStore.data.first()
                val versionId = prefs[BiblePrefs.SELECTED_VERSION]
                    ?: BibleVersions.default.id

                var copyright: String? = null
                val verses = when {
                    versionId == BibleVersions.ESV_ID -> {
                        val key = prefs[BiblePrefs.ESV_API_KEY]
                            ?: throw IllegalStateException("ESV key missing — set it in Versions.")
                        copyright = "Scripture quotations are from the ESV® Bible, " +
                            "© 2001 by Crossway. Used by permission. All rights reserved."
                        Bible.store.esvChapter(key, s.bookIndex, s.chapter)
                    }
                    BibleVersions.isApiBible(versionId) -> {
                        val key = prefs[BiblePrefs.APIBIBLE_KEY]
                            ?: throw IllegalStateException("API.Bible key missing — set it in Versions.")
                        val (v, cr) = Bible.store.apiBibleChapter(
                            key,
                            BibleVersions.apiBibleId(versionId),
                            s.bookIndex,
                            s.chapter,
                        )
                        copyright = cr.ifBlank { null }
                        v
                    }
                    else -> Bible.store.loadChapter(versionId, s.bookIndex, s.chapter)
                }

                val current = state.value
                state.value = current.copy(
                    versionId = versionId,
                    verses = verses,
                    loading = false,
                    error = if (verses.isEmpty()) "Chapter not found in this version." else null,
                    // A verse arrived at via Lookup/Search starts out highlighted
                    highlighted = current.scrollTarget?.let { current.highlighted + it }
                        ?: current.highlighted,
                    copyright = copyright,
                )
            } catch (e: Exception) {
                state.value = state.value.copy(
                    loading = false,
                    error = e.message ?: "Could not load chapter.",
                )
            }
        }
    }

    fun toggleHighlight(verse: Int) {
        val s = state.value
        val set = s.highlighted.toMutableSet()
        if (!set.add(verse)) set.remove(verse)
        state.value = s.copy(highlighted = set)
    }

    fun consumeScrollTarget() {
        state.value = state.value.copy(scrollTarget = null)
    }

    fun step(direction: Int) {
        val s = state.value
        val book = BibleBooks.all[s.bookIndex]
        var newBook = s.bookIndex
        var newChapter = s.chapter + direction

        if (newChapter < 1) {
            if (s.bookIndex == 0) return
            newBook = s.bookIndex - 1
            newChapter = BibleBooks.all[newBook].chapters
        } else if (newChapter > book.chapters) {
            if (s.bookIndex == BibleBooks.all.size - 1) return
            newBook = s.bookIndex + 1
            newChapter = 1
        }

        state.value = ReaderState(bookIndex = newBook, chapter = newChapter)
        load()
    }
}

class ReaderScreen(
    sealedActivity: SealedLightActivity,
    private val bookIndex: Int,
    private val chapter: Int,
    private val highlightVerse: Int? = null,
) : LightScreen<Unit, ReaderViewModel>(sealedActivity) {

    override val viewModelClass: Class<ReaderViewModel>
        get() = ReaderViewModel::class.java

    override fun createViewModel(): ReaderViewModel {
        Bible.init(lightContext.filesDir)
        return ReaderViewModel(lightContext.dataStore, bookIndex, chapter, highlightVerse)
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val book = BibleBooks.all[state.bookIndex]
        val listState = rememberLazyListState()

        // Scroll once to the lookup/search target verse
        LaunchedEffect(state.scrollTarget, state.verses) {
            val target = state.scrollTarget
            if (target != null && state.verses.isNotEmpty()) {
                val index = state.verses.indexOfFirst { it.verse == target }
                if (index >= 0) listState.scrollToItem(index)
                viewModel.consumeScrollTarget()
            }
        }

        BibleScreenSurface {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.TwoLineDetail(
                        line1 = "${book.name} ${state.chapter}",
                        line2 = if (BibleVersions.isApiBible(state.versionId)) {
                            "API.Bible"
                        } else {
                            state.versionId
                        },
                    ),
                    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 1.5f.gridUnitsAsDp())
                        .weight(1f),
                ) {
                    if (state.loading) {
                        item {
                            LightText(
                                text = "Loading…",
                                variant = LightTextVariant.Copy,
                                lighten = true,
                                modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                            )
                        }
                    }
                    state.error?.let { err ->
                        item {
                            LightText(
                                text = err,
                                variant = LightTextVariant.Copy,
                                lighten = true,
                                modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                            )
                        }
                    }
                    itemsIndexed(state.verses) { _, verse ->
                        val isHighlighted =
                            state.highlighted.contains(verse.verse) ||
                                state.scrollTarget == verse.verse
                        VerseRow(
                            verse = verse,
                            highlighted = isHighlighted,
                            onTap = { viewModel.toggleHighlight(verse.verse) },
                        )
                    }
                    // Publisher attribution (required for licensed versions)
                    val copyrightLine = state.copyright
                    if (copyrightLine != null && state.verses.isNotEmpty()) {
                        item {
                            LightText(
                                text = copyrightLine,
                                variant = LightTextVariant.Superfine,
                                lighten = true,
                                modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                            )
                        }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.REWIND,
                            onClick = { viewModel.step(-1) },
                            contentDescription = "previous chapter",
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.LIST,
                            onClick = {
                                navigateTo({ sa -> ChaptersScreen(sa, viewModel.state.value.bookIndex) })
                            },
                            contentDescription = "chapters",
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.SEARCH,
                            onClick = { navigateTo(::SearchScreen) },
                            contentDescription = "search",
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.FAST_FORWARD,
                            onClick = { viewModel.step(1) },
                            contentDescription = "next chapter",
                        ),
                    ),
                )
            }
        }
    }

    @Composable
    private fun VerseRow(verse: VerseJson, highlighted: Boolean, onTap: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (highlighted) {
                        Modifier.background(LightThemeTokens.colors.contentSecondary.copy(alpha = 0.25f))
                    } else {
                        Modifier
                    }
                )
                .lightClickable { onTap() }
                .padding(vertical = 0.3f.gridUnitsAsDp()),
        ) {
            LightText(
                text = "${verse.verse}",
                variant = LightTextVariant.Superfine,
                lighten = true,
                modifier = Modifier
                    .width(1.6f.gridUnitsAsDp())
                    .padding(top = 0.15f.gridUnitsAsDp()),
            )
            LightText(
                text = verse.text,
                variant = LightTextVariant.Paragraph,
            )
        }
    }
}
