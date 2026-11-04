package dev.bunnyloader.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Asks for a name (file, folder, rename). The current name comes selected up to the extension. */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val stemEnd = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stemEnd))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val name = value.text.trim()
    PixelDialog(title, onDismiss, buttons = {
        PixelButton(stringResource(R.string.Cancel), onDismiss, fontSize = Ts.Body)
        PixelButton(confirm, { onConfirm(name) }, fill = Bl.TitleFill, fontSize = Ts.Body,
            enabled = name.isNotEmpty() && name != initial)
    }) {
        // File names use a regular font: in the pixel one, "l" and "1" look alike.
        BasicTextField(
            value, { value = it },
            Modifier.fillMaxWidth().pixelPanel(fill = Bl.Select, raised = false).padding(12.dp)
                .focusRequester(focus),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrect = false),
            keyboardActions = KeyboardActions(onDone = { if (name.isNotEmpty() && name != initial) onConfirm(name) }),
            textStyle = TextStyle(color = Bl.Text, fontSize = 16.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Bl.PressedText),
        )
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    PixelDialog(title, onDismiss, buttons = {
        PixelButton(stringResource(R.string.Cancel), onDismiss, fontSize = Ts.Body)
        PixelButton(confirm, onConfirm, fill = Bl.Bad.mix(Bl.Night, 0.35f), fontSize = Ts.Body)
    }) {
        PixelText(message, Ts.Body, Bl.TextDim)
    }
}

@Composable
fun UnsavedDialog(onDismiss: () -> Unit, onDiscard: () -> Unit, onSave: () -> Unit) {
    PixelDialog(stringResource(R.string.UnsavedTitle), onDismiss, buttons = {
        PixelButton(stringResource(R.string.DontSave), onDiscard, fontSize = Ts.Body)
        PixelButton(stringResource(R.string.Save), onSave, fill = Bl.TitleFill, fontSize = Ts.Body)
    }) {
        PixelText(stringResource(R.string.UnsavedMessage), Ts.Body, Bl.TextDim)
    }
}

/** A whole, copyable error: it is the text the modder is going to search for. */
@Composable
fun ErrorDialog(title: String, message: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    PixelDialog(title, onDismiss, buttons = {
        PixelButton(stringResource(R.string.Copy), { clipboard.setText(AnnotatedString(message)) }, fontSize = Ts.Body)
        PixelButton(stringResource(R.string.Close), onDismiss, fill = Bl.TitleFill, fontSize = Ts.Body)
    }) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 320.dp).framePanel(fill = Bl.Code)
                .verticalScroll(rememberScrollState()).padding(12.dp),
        ) {
            SelectionContainer {
                Text(message, color = Bl.TextDim, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }
    }
}
