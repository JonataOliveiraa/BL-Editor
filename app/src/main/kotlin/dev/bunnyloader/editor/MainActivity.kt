package dev.bunnyloader.editor

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { Highlighting.init(this) }
            .onFailure { Log.e(TAG, "Syntax highlighting is unavailable", it) }
        setContent { EditorTheme { EditorApp() } }
    }

    companion object {
        const val TAG = "BLEditor"
    }
}
