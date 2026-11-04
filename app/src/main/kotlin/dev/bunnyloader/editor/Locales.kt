package dev.bunnyloader.editor

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale

/** The languages Bunny Loader has, in their own names. "" follows the device. */
object Locales {
    val ALL = listOf(
        "en-US" to "English",
        "es-ES" to "Español",
        "pt-BR" to "Português (Brasil)",
        "ru-RU" to "Русский",
        "th-TH" to "ไทย",
        "vi-VN" to "Tiếng Việt",
        "zh-Hans" to "简体中文",
    )

    fun wrap(base: Context): Context {
        val code = EditorPrefs.language(base)
        if (code.isEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(code)))
        return base.createConfigurationContext(config)
    }
}

@Composable
fun LanguageDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val current = EditorPrefs.language(context)
    fun choose(code: String) {
        onDismiss()
        if (code == current) return
        EditorPrefs.setLanguage(context, code)
        (context as? Activity)?.recreate()
    }
    PixelDialog(stringResource(R.string.Language), onDismiss, buttons = {
        PixelButton(stringResource(R.string.Cancel), onDismiss, fontSize = Ts.Body)
    }) {
        Column {
            LanguageRow(stringResource(R.string.Automatic), current.isEmpty()) { choose("") }
            Locales.ALL.forEach { (code, name) -> LanguageRow(name, current == code) { choose(code) } }
        }
    }
}

@Composable
private fun LanguageRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().pixelClickable(PixelCorner.NONE, onClick = onClick).padding(horizontal = 8.dp, vertical = 9.dp),
    ) {
        PixelText(name, Ts.Item, if (selected) Bl.PressedText else Bl.Text)
    }
}
