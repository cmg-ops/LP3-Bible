package com.dailyhobbyist.bible

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A modal question shown over the reader. */
sealed interface ReaderDialog {
    data object None : ReaderDialog
    data object SavePosition : ReaderDialog
    data class SaveVerse(val verse: VerseJson) : ReaderDialog
    data class RemoveVerse(val verse: VerseJson) : ReaderDialog
}

data class ReaderState(
    val bookIndex: Int,
    val chapter: Int,
    val versionId: String = "",
    val verses: List<VerseJson> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** Verse numbers the reader has tapped — session only, never saved. */
    val highlighted: Set<Int> = emptySet(),
    /** Verse numbers that are permanently saved (bookmarks). */
    val savedVerses: Set<Int> = emptySet(),
    /** One-time target verse to scroll to (from Where I Left Off or Search). */
    val scrollTarget: Int? = null,
    /** Publisher copyright line for online versions (shown after the last verse). */
    val copyright: String? = null,
    val dialog: ReaderDialog = ReaderDialog.None,
    val toast: String? = null,
    /** True when the pending long-press dialog added a highlight we should undo on cancel. */
    val longPressAddedHighlight: Boolean = false,
    /** True when this chapter is the saved reading position for this version. */
    val isBookmarkedSpot: Boolean = false,
)

class ReaderViewModel(
    private val dataStore: DataStore<Preferences>,
    private val repository: BibleRepository,
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

                // Which verses in this chapter are already saved?
                val saved = withContext(Dispatchers.IO) {
                    verses.filter {
                        repository.isSaved(versionId, s.bookIndex, s.chapter, it.verse)
                    }.map { it.verse }.toSet()
                }

                // Is this chapter the saved reading position for this version?
                val savedPosition = withContext(Dispatchers.IO) {
                    repository.getPosition(versionId)
                }
                val isSpot = savedPosition != null &&
                    savedPosition.bookIndex == s.bookIndex &&
                    savedPosition.chapter == s.chapter

                val current = state.value
                state.value = current.copy(
                    versionId = versionId,
                    verses = verses,
                    loading = false,
                    error = if (verses.isEmpty()) "Chapter not found in this version." else null,
                    highlighted = current.scrollTarget?.let { current.highlighted + it }
                        ?: current.highlighted,
                    savedVerses = saved,
                    isBookmarkedSpot = isSpot,
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

    // ── Long-press → ask to save or remove ──
    fun onVerseLongPress(verse: VerseJson) {
        val s = state.value
        // Remember whether it was already highlighted, so Cancel can restore state
        val wasHighlighted = s.highlighted.contains(verse.verse)
        state.value = s.copy(
            highlighted = s.highlighted + verse.verse,
            longPressAddedHighlight = !wasHighlighted,
            dialog = if (s.savedVerses.contains(verse.verse)) {
                ReaderDialog.RemoveVerse(verse)
            } else {
                ReaderDialog.SaveVerse(verse)
            },
        )
    }

    fun confirmSaveVerse(verse: VerseJson) {
        viewModelScope.launch {
            val s = state.value
            withContext(Dispatchers.IO) {
                repository.toggleSave(
                    versionId = s.versionId,
                    bookIndex = s.bookIndex,
                    chapter = s.chapter,
                    verse = verse.verse,
                    text = verse.text,
                )
            }
            state.value = state.value.copy(
                savedVerses = state.value.savedVerses + verse.verse,
                dialog = ReaderDialog.None,
                longPressAddedHighlight = false,
                toast = "Saved",
            )
        }
    }

    fun confirmRemoveVerse(verse: VerseJson) {
        viewModelScope.launch {
            val s = state.value
            withContext(Dispatchers.IO) {
                repository.toggleSave(
                    versionId = s.versionId,
                    bookIndex = s.bookIndex,
                    chapter = s.chapter,
                    verse = verse.verse,
                    text = verse.text,
                )
            }
            state.value = state.value.copy(
                savedVerses = state.value.savedVerses - verse.verse,
                dialog = ReaderDialog.None,
                longPressAddedHighlight = false,
                toast = "Removed",
            )
        }
    }

    // ── Bookmark icon → save reading position ──
    fun askSavePosition() {
        state.value = state.value.copy(dialog = ReaderDialog.SavePosition)
    }

    fun confirmSavePosition() {
        viewModelScope.launch {
            val s = state.value
            // Save the first visible verse (or verse 1) as the resume point
            val verse = s.scrollTarget ?: s.verses.firstOrNull()?.verse ?: 1
            withContext(Dispatchers.IO) {
                repository.savePosition(s.versionId, s.bookIndex, s.chapter, verse)
            }
            state.value = state.value.copy(
                dialog = ReaderDialog.None,
                isBookmarkedSpot = true,
                toast = "Spot saved",
            )
        }
    }

    fun dismissDialog() {
        val s = state.value
        // If the long-press added a highlight purely to show the target, remove it on cancel
        val restored = if (s.longPressAddedHighlight) {
            val pendingVerse = when (val d = s.dialog) {
                is ReaderDialog.SaveVerse -> d.verse.verse
                is ReaderDialog.RemoveVerse -> d.verse.verse
                else -> null
            }
            if (pendingVerse != null) s.highlighted - pendingVerse else s.highlighted
        } else {
            s.highlighted
        }
        state.value = s.copy(
            dialog = ReaderDialog.None,
            highlighted = restored,
            longPressAddedHighlight = false,
        )
    }

    fun clearToast() {
        state.value = state.value.copy(toast = null)
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

        // keep the current version; only reset chapter-scoped fields
        state.value = ReaderState(
            bookIndex = newBook,
            chapter = newChapter,
            versionId = s.versionId,
        )
        load()
    }

    override fun onBackPressed(): Boolean {
        return if (state.value.dialog != ReaderDialog.None) {
            dismissDialog()
            true
        } else {
            false
        }
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
        return ReaderViewModel(
            lightContext.dataStore,
            lightContext.bibleRepository(),
            bookIndex,
            chapter,
            highlightVerse,
        )
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val book = BibleBooks.all[state.bookIndex]
        val listState = rememberLazyListState()

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
                    rightButton = LightBarButton.Icon(
                        painter = androidx.compose.ui.res.painterResource(
                            id = if (state.isBookmarkedSpot) {
                                R.drawable.ic_bookmark_white
                            } else {
                                R.drawable.ic_bookmark_outline_white
                            },
                        ),
                        onClick = { viewModel.askSavePosition() },
                        contentDescription = "save reading position",
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
                            saved = state.savedVerses.contains(verse.verse),
                            onTap = { viewModel.toggleHighlight(verse.verse) },
                            onLongPress = { viewModel.onVerseLongPress(verse) },
                        )
                    }
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

            // ── Dialogs ──
            when (val d = state.dialog) {
                is ReaderDialog.SavePosition -> ConfirmDialog(
                    message = "Bookmark this spot?\n${book.name} ${state.chapter}",
                    confirmLabel = "SAVE",
                    onConfirm = { viewModel.confirmSavePosition() },
                    onCancel = { viewModel.dismissDialog() },
                )
                is ReaderDialog.SaveVerse -> ConfirmDialog(
                    message = "Save this verse?\n${book.name} ${state.chapter}:${d.verse.verse}",
                    confirmLabel = "SAVE",
                    onConfirm = { viewModel.confirmSaveVerse(d.verse) },
                    onCancel = { viewModel.dismissDialog() },
                )
                is ReaderDialog.RemoveVerse -> ConfirmDialog(
                    message = "Remove this saved verse?\n${book.name} ${state.chapter}:${d.verse.verse}",
                    confirmLabel = "REMOVE",
                    onConfirm = { viewModel.confirmRemoveVerse(d.verse) },
                    onCancel = { viewModel.dismissDialog() },
                )
                is ReaderDialog.None -> {}
            }
        }
    }

    @Composable
    private fun VerseRow(
        verse: VerseJson,
        highlighted: Boolean,
        saved: Boolean,
        onTap: () -> Unit,
        onLongPress: () -> Unit,
    ) {
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
                .combinedClickable(
                    onClick = { onTap() },
                    onLongClick = { onLongPress() },
                )
                .padding(vertical = 0.3f.gridUnitsAsDp()),
        ) {
            LightText(
                text = if (saved) "${verse.verse}\u00A0•" else "${verse.verse}",
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

    @Composable
    private fun ConfirmDialog(
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
    ) {
        // Full-screen dim scrim; tap anywhere outside the panel to cancel.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background.copy(alpha = 0.92f))
                .lightClickable { onCancel() },
            verticalArrangement = Arrangement.Center,
        ) {
            // Panel — swallows taps so they don't hit the scrim.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { }
                    .padding(horizontal = 2f.gridUnitsAsDp()),
            ) {
                LightText(
                    text = message,
                    variant = LightTextVariant.Heading,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2f.gridUnitsAsDp()),
                    horizontalArrangement = Arrangement.spacedBy(3f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = "CANCEL",
                        variant = LightTextVariant.Button,
                        lighten = true,
                        modifier = Modifier
                            .lightClickable { onCancel() }
                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                    )
                    LightText(
                        text = confirmLabel,
                        variant = LightTextVariant.Button,
                        modifier = Modifier
                            .lightClickable { onConfirm() }
                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}
