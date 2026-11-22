package dev.neri.iptv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent { App(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // Permite mandar a lista pelo adb em vez de digitar no controle:
    // adb shell am start -n dev.neri.iptv/.MainActivity --es playlist_url "<url>" [--es name "<nome>"]
    private fun handleIntent(intent: Intent?) {
        val url = intent?.getStringExtra("playlist_url") ?: return
        vm.addFromIntent(url, intent.getStringExtra("name"))
    }
}

@Composable
fun App(vm: MainViewModel) {
    MaterialTheme(colorScheme = TvColors) {
        Surface(Modifier.fillMaxSize()) {
            BackHandler(enabled = vm.canGoBack) { vm.back() }
            when (val screen = vm.screen) {
                Screen.Hub -> HubScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
                is Screen.Form -> FormScreen(vm, screen.editing)
                Screen.Watch -> WatchScreen(vm)
            }
        }
    }
}
