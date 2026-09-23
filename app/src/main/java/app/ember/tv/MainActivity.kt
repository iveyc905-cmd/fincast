package app.ember.tv

import android.graphics.Color as AndroidColor
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ember.tv.ui.components.VSpace
import app.ember.tv.ui.mobile.MobileApp
import app.ember.tv.ui.player.TvPlayerScreen
import app.ember.tv.ui.setup.SetupScreen
import app.ember.tv.ui.theme.EmberTheme
import app.ember.tv.util.CrashLog
import app.ember.tv.util.Pip
import app.ember.tv.util.isTelevision

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind transparent system bars, with light icons for the dark UI.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // A player that dims mid-match is worse than useless.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val tv = isTelevision()
        setContent {
            EmberTheme(tv = tv) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    EmberRoot(tv)
                    CrashReportDialog()
                }
            }
        }
    }

    // Android 8-11 need a nudge to shrink into picture-in-picture on Home.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        Pip.enterIfWanted(this)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Pip.inPip = isInPictureInPictureMode
    }
}

private enum class Screen { LOADING, SETUP, PLAYER }

@Composable
private fun EmberRoot(tv: Boolean) {
    val playlists by Graph.playlists.observePlaylists()
        .collectAsStateWithLifecycle(initialValue = null)
    var screen by remember { mutableStateOf(Screen.LOADING) }

    // First run lands on setup; afterwards the player is the home screen and
    // setup is reachable from it.
    LaunchedEffect(playlists) {
        val list = playlists ?: return@LaunchedEffect
        if (screen == Screen.LOADING) {
            screen = if (list.isEmpty()) Screen.SETUP else Screen.PLAYER
        }
    }

    // Back from settings returns to the player rather than closing the app.
    BackHandler(enabled = screen == Screen.SETUP && !playlists.isNullOrEmpty()) {
        screen = Screen.PLAYER
    }

    when (screen) {
        Screen.LOADING -> Unit
        Screen.SETUP -> SetupScreen(onDone = { screen = Screen.PLAYER })
        Screen.PLAYER -> {
            val openSettings = { screen = Screen.SETUP }
            if (tv) TvPlayerScreen(onOpenSettings = openSettings)
            else MobileApp()
        }
    }
}

/** Shows the previous run's crash, if there was one, so it can be copied and reported. */
@Composable
private fun CrashReportDialog() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var report by remember { mutableStateOf(CrashLog.read(context)) }

    val dismiss = {
        CrashLog.clear(context)
        report = null
    }

    report?.let { text ->
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Ember closed unexpectedly") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Copy this report and send it along so the problem can be fixed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    VSpace(10)
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Dismiss") } },
        )
    }
}
