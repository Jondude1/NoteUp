package com.jondude.noteup

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jondude.noteup.audio.Recorder
import com.jondude.noteup.data.Settings
import com.jondude.noteup.data.Store
import com.jondude.noteup.ui.FlashOverlay
import com.jondude.noteup.ui.HomeScreen
import com.jondude.noteup.ui.InkTheme
import com.jondude.noteup.ui.NotebookScreen
import com.jondude.noteup.ui.RecordingScreen
import com.jondude.noteup.ui.Tab

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        Settings.init(applicationContext)
        setContent {
            val recording = Recorder.active != null
            LaunchedEffect(recording) {
                // keep the screen awake while a meeting is being recorded
                if (recording) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            InkTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    App()
                    FlashOverlay()
                }
            }
        }
    }
}

/** Screen route encoded as a string so it survives rotation: "home:NOTES", "nb:<id>:<page>", "rec:<id>". */
@Composable
private fun App() {
    var route by rememberSaveable { mutableStateOf("home:NOTES") }
    var lastTab by rememberSaveable { mutableStateOf("NOTES") }
    val parts = route.split(":")

    fun home() { route = "home:$lastTab" }

    when (parts[0]) {
        "nb" -> {
            BackHandler { home() }
            key(route) {
                NotebookScreen(
                    nbId = parts[1],
                    startPage = parts.getOrNull(2)?.toIntOrNull() ?: 0,
                    onBack = { home() },
                    onOpenRecording = { route = "rec:$it" },
                )
            }
        }
        "rec" -> {
            BackHandler { home() }
            RecordingScreen(
                id = parts[1],
                onBack = { home() },
                onOpenNotebook = { nbId, pageId ->
                    val idx = pageId?.let { pid -> Store.notebook(nbId)?.pageIds?.indexOf(pid) }?.coerceAtLeast(0) ?: 0
                    route = "nb:$nbId:$idx"
                },
            )
        }
        else -> {
            val tab = runCatching { Tab.valueOf(parts.getOrElse(1) { "NOTES" }) }.getOrDefault(Tab.NOTES)
            HomeScreen(
                tab = tab,
                onTab = { lastTab = it.name; route = "home:${it.name}" },
                onOpenNotebook = { route = "nb:$it:0" },
                onOpenRecording = { route = "rec:$it" },
            )
        }
    }
}
