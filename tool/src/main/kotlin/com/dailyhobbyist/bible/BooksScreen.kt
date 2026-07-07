package com.dailyhobbyist.bible

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import androidx.compose.ui.unit.dp

/** Old Testament / New Testament book list */
class BooksScreen(
    sealedActivity: SealedLightActivity,
    private val isOldTestament: Boolean,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val books = if (isOldTestament) BibleBooks.oldTestament else BibleBooks.newTestament
        val title = if (isOldTestament) "Old Testament" else "New Testament"

        BibleScreenSurface {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text(title),
                    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 1.5f.gridUnitsAsDp()),
                ) {
                    items(books) { book ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable {
                                    navigateTo({ sa -> ChaptersScreen(sa, book.index) })
                                }
                                .padding(vertical = 0.7f.gridUnitsAsDp()),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LightText(
                                text = book.name,
                                variant = LightTextVariant.Copy,
                            )
                            LightText(
                                text = "${book.chapters}",
                                variant = LightTextVariant.Detail,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Chapter number grid.
 * When [targetChapter] is set (arriving from Lookup), that cell is
 * drawn with a border, and tapping it carries [targetVerse] into the reader.
 */
class ChaptersScreen(
    sealedActivity: SealedLightActivity,
    private val bookIndex: Int,
    private val targetChapter: Int? = null,
    private val targetVerse: Int? = null,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val book = BibleBooks.all[bookIndex]

        BibleScreenSurface {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text(book.name),
                    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 1.5f.gridUnitsAsDp()),
                ) {
                    items((1..book.chapters).toList()) { ch ->
                        val isTarget = ch == targetChapter
                        Box(
                            modifier = Modifier
                                .padding(0.25f.gridUnitsAsDp())
                                .aspectRatio(1.4f)
                                .then(
                                    if (isTarget) Modifier.border(
                                        width = 1.dp,
                                        color = LightThemeTokens.colors.content,
                                    ) else Modifier
                                )
                                .lightClickable {
                                    val highlight = if (ch == targetChapter) targetVerse else null
                                    navigateTo({ sa ->
                                        ReaderScreen(sa, bookIndex, ch, highlight)
                                    })
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            LightText(
                                text = "$ch",
                                variant = LightTextVariant.Copy,
                            )
                        }
                    }
                }
            }
        }
    }
}
