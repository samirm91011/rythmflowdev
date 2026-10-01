package com.rhythmandflow.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import com.rhythmandflow.app.ui.nav.AppRoot
import com.rhythmandflow.app.ui.theme.RhythmTheme
import com.rhythmandflow.app.ui.viewmodel.SessionViewModel
import com.rhythmandflow.app.ui.viewmodel.appViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RhythmTheme {
                Root()
            }
        }
    }
}

@Composable
private fun Root() {
    val session = appViewModel(key = "session") { SessionViewModel(it) }
    AppRoot(session)
}
