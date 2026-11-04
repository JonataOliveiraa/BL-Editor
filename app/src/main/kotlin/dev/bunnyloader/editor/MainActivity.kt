package dev.bunnyloader.editor

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.bunnyloader.editor.csharp.CSharpApi

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Locales.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { Highlighting.init(this) }
            .onFailure { Log.e(TAG, "Syntax highlighting is unavailable", it) }
        // The API tree loads ahead of the first .cs file, so the first completion has no wait.
        Thread({ CSharpApi.tree(this) }, "api-tree").apply { priority = Thread.MIN_PRIORITY }.start()
        setContent { EditorTheme { EditorApp() } }
    }

    companion object {
        const val TAG = "BLEditor"
    }
}
