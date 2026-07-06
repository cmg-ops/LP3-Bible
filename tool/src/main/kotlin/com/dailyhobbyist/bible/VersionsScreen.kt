package com.dailyhobbyist.bible

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
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

data class VersionRowState(
    val info: VersionInfo,
    val downloaded: Boolean,
    val selected: Boolean,
    val downloadProgress: Float?,   // non-null while downloading
    val error: String?,
    val isEsv: Boolean = false,
    val esvKeySet: Boolean = false,
    /** Set when this row is a user-enabled API.Bible version. */
    val apiBibleSaved: SavedApiBibleVersion? = null,
    /** Set on the "API.Bible" setup/manage row. */
    val isApiBibleManage: Boolean = false,
)

class VersionsViewModel(
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    private val jsonCodec = Json { ignoreUnknownKeys = true }

    val rows = MutableStateFlow<List<VersionRowState>>(emptyList())

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val prefs = dataStore.data.first()
            val selectedId = prefs[BiblePrefs.SELECTED_VERSION]
            val esvKey = prefs[BiblePrefs.ESV_API_KEY]

            val offline = BibleVersions.catalog.map { v ->
                VersionRowState(
                    info = v,
                    downloaded = Bible.store.isDownloaded(v.id),
                    selected = v.id == selectedId,
                    downloadProgress = null,
                    error = null,
                )
            }
            val esvRow = VersionRowState(
                info = BibleVersions.esv,
                downloaded = false,
                selected = selectedId == BibleVersions.ESV_ID,
                downloadProgress = null,
                error = null,
                isEsv = true,
                esvKeySet = !esvKey.isNullOrBlank(),
            )

            // User-enabled API.Bible versions (NIV, NKJV, ...)
            val savedJson = prefs[BiblePrefs.APIBIBLE_VERSIONS]
            val saved: List<SavedApiBibleVersion> = if (savedJson.isNullOrBlank()) {
                emptyList()
            } else {
                try {
                    jsonCodec.decodeFromString(savedJson)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            val abRows = saved.map { s ->
                val fullId = BibleVersions.APIBIBLE_PREFIX + s.id
                VersionRowState(
                    info = VersionInfo(
                        id = fullId,
                        name = s.name,
                        year = s.abbr,
                        description = "via API.Bible",
                        url = "",
                    ),
                    downloaded = false,
                    selected = selectedId == fullId,
                    downloadProgress = null,
                    error = null,
                    apiBibleSaved = s,
                )
            }
            val manageRow = VersionRowState(
                info = VersionInfo(
                    id = "APIBIBLE_MANAGE",
                    name = "API.Bible",
                    year = "",
                    description = "",
                    url = "",
                ),
                downloaded = false,
                selected = false,
                downloadProgress = null,
                error = null,
                isApiBibleManage = true,
            )
            rows.value = offline + esvRow + abRows + manageRow
        }
    }

    enum class TapResult { None, OpenEsvKey, OpenApiBible }

    fun rowTapped(row: VersionRowState): TapResult {
        if (row.downloadProgress != null) return TapResult.None
        if (row.isApiBibleManage) return TapResult.OpenApiBible
        if (row.isEsv) {
            return if (row.esvKeySet) {
                select(row.info)
                TapResult.None
            } else {
                TapResult.OpenEsvKey
            }
        }
        if (row.apiBibleSaved != null) {
            select(row.info)
            return TapResult.None
        }
        if (row.downloaded) select(row.info) else download(row.info)
        return TapResult.None
    }

    private fun select(version: VersionInfo) {
        viewModelScope.launch {
            dataStore.edit { it[BiblePrefs.SELECTED_VERSION] = version.id }
            refresh()
        }
    }

    private fun download(version: VersionInfo) {
        viewModelScope.launch {
            updateRow(version.id) { it.copy(downloadProgress = 0f, error = null) }
            try {
                Bible.store.download(version) { p ->
                    updateRow(version.id) { it.copy(downloadProgress = p) }
                }
                // Auto-select a version the user just downloaded
                dataStore.edit { it[BiblePrefs.SELECTED_VERSION] = version.id }
                refresh()
            } catch (e: Exception) {
                updateRow(version.id) {
                    it.copy(
                        downloadProgress = null,
                        error = "Download failed — check Wi-Fi",
                    )
                }
            }
        }
    }

    fun delete(row: VersionRowState) {
        if (!row.downloaded || row.selected) return
        viewModelScope.launch {
            Bible.store.delete(row.info.id)
            refresh()
        }
    }

    private fun updateRow(id: String, transform: (VersionRowState) -> VersionRowState) {
        rows.value = rows.value.map { if (it.info.id == id) transform(it) else it }
    }
}

class VersionsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, VersionsViewModel>(sealedActivity) {

    override val viewModelClass: Class<VersionsViewModel>
        get() = VersionsViewModel::class.java

    override fun createViewModel(): VersionsViewModel {
        Bible.init(lightContext.filesDir)
        return VersionsViewModel(lightContext.dataStore)
    }

    @Composable
    override fun Content() {
        val rows by viewModel.rows.collectAsState()

        BibleScreenSurface {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text("Versions"),
                    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 1.5f.gridUnitsAsDp()),
                ) {
                    items(rows) { row ->
                        VersionRow(row)
                    }
                }
            }
        }
    }

    @Composable
    private fun VersionRow(row: VersionRowState) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable {
                    when (viewModel.rowTapped(row)) {
                        VersionsViewModel.TapResult.OpenEsvKey -> navigateTo(::EsvKeyScreen)
                        VersionsViewModel.TapResult.OpenApiBible -> navigateTo(::ApiBibleScreen)
                        VersionsViewModel.TapResult.None -> {}
                    }
                }
                .padding(vertical = 0.7f.gridUnitsAsDp()),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                LightText(
                    text = row.info.name,
                    variant = LightTextVariant.Copy,
                )
                val sub = when {
                    row.downloadProgress != null ->
                        "Downloading… ${(row.downloadProgress * 100).toInt()}%"
                    row.error != null -> row.error
                    row.isApiBibleManage -> "Add NIV, NKJV & more with your free key"
                    row.apiBibleSaved != null && row.selected -> "Online · Selected"
                    row.apiBibleSaved != null -> "Online · Tap to select"
                    row.isEsv && row.selected -> "Online · Selected"
                    row.isEsv && row.esvKeySet -> "Online · Tap to select"
                    row.isEsv -> "Online · free key required, tap to set up"
                    row.selected -> "${row.info.year} · Selected"
                    row.downloaded -> "${row.info.year} · Tap to select"
                    else -> "${row.info.year} · ${row.info.description}"
                }
                LightText(
                    text = sub,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
            }

            when {
                row.downloadProgress != null -> {
                    // no icon while downloading; percentage is in the subtitle
                }
                row.selected -> {
                    LightIcon(
                        icon = LightIcons.ACCEPT,
                        contentDescription = "selected",
                    )
                }
                row.isApiBibleManage -> {
                    LightIcon(
                        icon = LightIcons.PENCIL,
                        contentDescription = "manage API.Bible",
                        modifier = Modifier.lightClickable { navigateTo(::ApiBibleScreen) },
                    )
                }
                row.apiBibleSaved != null -> {
                    // selectable online row; check shows when selected (handled above)
                }
                row.isEsv -> {
                    LightIcon(
                        icon = LightIcons.PENCIL,
                        contentDescription = "set up ESV key",
                        modifier = Modifier.lightClickable { navigateTo(::EsvKeyScreen) },
                    )
                }
                row.downloaded -> {
                    LightIcon(
                        icon = LightIcons.TRASH,
                        contentDescription = "delete version",
                        modifier = Modifier.lightClickable { viewModel.delete(row) },
                    )
                }
                else -> {
                    LightIcon(
                        icon = LightIcons.DOWNLOAD_ARROW,
                        contentDescription = "download version",
                    )
                }
            }
        }
    }
}
