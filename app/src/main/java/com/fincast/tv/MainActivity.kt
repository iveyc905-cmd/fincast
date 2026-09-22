package com.fincast.tv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fincast.tv.ui.player.PlayerScreen
import com.fincast.tv.ui.setup.SetupScreen
import com.fincast.tv.ui.theme.FincastTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A living-room app that dims mid-match is worse than useless.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            FincastTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    FincastRoot()
                }
            }
        }
    }
}

private enum class Screen { LOADING, SETUP, PLAYER }

@Composable
private fun FincastRoot() {
    val playlists by Graph.playlists.observePlaylists()
        .collectAsStateWithLifecycle(initialValue = null)
    var screen by remember { mutableStateOf(Screen.LOADING) }

    // First run lands on setup; afterwards the player is the home screen and
    // setup is reachable from the menu.
    LaunchedEffect(playlists) {
        val list = playlists ?: return@LaunchedEffect
        if (screen == Screen.LOADING) {
            screen = if (list.isEmpty()) Screen.SETUP else Screen.PLAYER
        }
    }

    when (screen) {
        Screen.LOADING -> Unit
        Screen.SETUP -> SetupScreen(onDone = { screen = Screen.PLAYER })
        Screen.PLAYER -> PlayerScreen(onOpenSettings = { screen = Screen.SETUP })
    }
}
