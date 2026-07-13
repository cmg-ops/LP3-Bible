package com.dailyhobbyist.bible

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
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

data class SavedRow(
    val entity: SavedVerseEntity,
    val reference: String,        // "John 3:16"
    val versionLabel: String,     // "NKJV"
    val dateLabel: String,        // "June 10, 2026"
)

data class SavedScreenState(
    val loading: Boolean = true,
    val currentVersionId: String = "",
    val currentVersionRows: List<SavedRow> = emptyList(),
    val otherVersionRows: List<SavedRow> = emptyList(),
    val editing: Boolean = false,
)

class SavedViewModel(
    private val dataStore: DataStore<Preferences>,
    private val repository: BibleRepository,
) : LightViewModel<Unit>() {

    val state = MutableStateFlow(SavedScreenState())

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val currentVersionId = dataStore.data.first()[BiblePrefs.SELECTED_VERSION]
                ?: BibleVersions.default.id
            val all = withContext(Dispatchers.IO) { repository.listAllSaves() }

            val rows = all.map { e ->
                val book = BibleBooks.all.getOrNull(e.bookIndex)
                SavedRow(
                    entity = e,
                    reference = "${book?.name ?: "?"} ${e.chapter}:${e.verse}",
                    versionLabel = versionLabel(e.versionId),
                    dateLabel = formatSavedDate(e.savedAt),
                )
            }
            state.value = SavedScreenState(
                loading = false,
                currentVersionId = currentVersionId,
                currentVersionRows = rows.filter { it.entity.versionId == currentVersionId },
                otherVersionRows = rows.filter { it.entity.versionId != currentVersionId },
                editing = state.value.editing,
            )
        }
    }

    fun toggleEditing() {
        state.value = state.value.copy(editing = !state.value.editing)
    }

    fun delete(row: SavedRow) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.deleteSave(row.entity.id) }
            refresh()
        }
    }

    private fun versionLabel(versionId: String): String = when {
        versionId == BibleVersions.ESV_ID -> "ESV"
        BibleVersions.isApiBible(versionId) -> "API.Bible"
        else -> versionId
    }
}

class SavedScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SavedViewModel>(sealedActivity) {

    override val viewModelClass: Class<SavedViewModel>
        get() = SavedViewModel::class.java

    override fun createViewModel(): SavedViewModel {
        Bible.init(lightContext.filesDir)
        return SavedViewModel(lightContext.dataStore, lightContext.bibleRepository())
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()

        BibleScreenSurface {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text("Saved"),
                    rightButton = LightBarButton.LightIcon(
                        icon = if (state.editing) LightIcons.ACCEPT else LightIcons.PENCIL,
                        onClick = { viewModel.toggleEditing() },
                        contentDescription = if (state.editing) "done editing" else "edit saves",
                    ),
                    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                )

                val empty = !state.loading &&
                    state.currentVersionRows.isEmpty() &&
                    state.otherVersionRows.isEmpty()

                if (state.loading) {
                    LightText(
                        text = "Loading…",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(horizontal = 1.5f.gridUnitsAsDp()),
                    )
                } else if (empty) {
                    LightText(
                        text = "No saved verses yet. Long-press a verse while reading to save it.",
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
                        if (state.currentVersionRows.isNotEmpty()) {
                            items2(state.currentVersionRows, state.editing)
                        }
                        if (state.otherVersionRows.isNotEmpty()) {
                            item {
                                LightText(
                                    text = "SAVED FROM OTHER VERSIONS",
                                    variant = LightTextVariant.Subheading,
                                    modifier = Modifier.padding(
                                        top = 2f.gridUnitsAsDp(),
                                        bottom = 0.75f.gridUnitsAsDp(),
                                    ),
                                )
                            }
                            items2(state.otherVersionRows, state.editing)
                        }
                    }
                }
            }
        }
    }

    // Extracted so both sections render rows identically.
    private fun LazyListScope.items2(
        rows: List<SavedRow>,
        editing: Boolean,
    ) {
        items(rows) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable {
                        if (!editing) openSavedRow(row)
                    }
                    .padding(vertical = 0.7f.gridUnitsAsDp()),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    LightText(
                        text = row.entity.text,
                        variant = LightTextVariant.Paragraph,
                    )
                    LightText(
                        text = "${row.reference} · ${row.versionLabel} · ${row.dateLabel}",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                    )
                }
                if (editing) {
                    LightIcon(
                        icon = LightIcons.DELETE,
                        contentDescription = "remove save",
                        modifier = Modifier
                            .padding(start = 1f.gridUnitsAsDp())
                            .lightClickable { viewModel.delete(row) },
                    )
                }
            }
        }
    }

    private fun openSavedRow(row: SavedRow) {
        // Opens the passage in whatever version is currently selected,
        // scrolled to and highlighting the saved verse.
        navigateTo({ sa ->
            ReaderScreen(
                sa,
                bookIndex = row.entity.bookIndex,
                chapter = row.entity.chapter,
                highlightVerse = row.entity.verse,
            )
        })
    }
}
