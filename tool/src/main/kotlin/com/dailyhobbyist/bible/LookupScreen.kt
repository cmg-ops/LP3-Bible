package com.dailyhobbyist.bible

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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

class LookupViewModel : LightViewModel<Unit>() {
    val message = MutableStateFlow<String?>(null)
}

/**
 * Type a reference like "John 3:16", "Ps 23", or just "Genesis".
 *
 * - Book only        -> chapter grid for that book
 * - Book + chapter   -> chapter grid with the chapter marked; tapping it opens the reader
 * - Book + ch:verse  -> chapter grid with the chapter marked; tapping it opens the
 *                       reader scrolled to that verse with it highlighted
 */
class LookupScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, LookupViewModel>(sealedActivity) {

    override val viewModelClass: Class<LookupViewModel>
        get() = LookupViewModel::class.java

    override fun createViewModel(): LookupViewModel = LookupViewModel()

    @Composable
    override fun Content() {
        val message by viewModel.message.collectAsState()
        val textFieldState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()

        BibleScreenSurface {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTextInputEditor(
                    title = "Lookup",
                    editorKey = "bible-lookup",
                    state = textFieldState,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    onSubmit = { text -> submit(text.toString()) },
                    onBack = { goBack() },
                    submitIcon = LightIcons.SEARCH,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                )
                message?.let { msg ->
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

    private fun submit(text: String) {
        val parsed = BibleBooks.parseReference(text)
        if (parsed == null) {
            viewModel.message.value =
                "Not recognized. Try \"John 3:16\", \"Ps 23\", or a book name."
            return
        }
        viewModel.message.value = null

        if (parsed.chapter != null && parsed.verse != null) {
            // Full reference: open the chapter grid with the target marked,
            // tapping the marked chapter highlights the verse in the reader.
            navigateTo({ sa ->
                ChaptersScreen(
                    sa,
                    bookIndex = parsed.book.index,
                    targetChapter = parsed.chapter,
                    targetVerse = parsed.verse,
                )
            })
        } else if (parsed.chapter != null) {
            navigateTo({ sa ->
                ChaptersScreen(
                    sa,
                    bookIndex = parsed.book.index,
                    targetChapter = parsed.chapter,
                )
            })
        } else {
            navigateTo({ sa -> ChaptersScreen(sa, bookIndex = parsed.book.index) })
        }
    }
}
