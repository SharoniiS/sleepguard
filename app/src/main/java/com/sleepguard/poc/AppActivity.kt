package com.sleepguard.poc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sleepguard.poc.ui.SleepApp
import com.sleepguard.poc.ui.SleepGuardTheme
import com.sleepguard.poc.ui.SplashScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The product launcher — the Compose UI (4 tabs). On open it shows a brief splash (the SleepGuard
 * poster) while the latest night data is collected/organized off the main thread, then reveals the
 * app. The old View-based [MainActivity] is kept as a debug screen (no longer the launcher).
 */
class AppActivity : ComponentActivity() {

    private val vm: SleepViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SleepGuardTheme {
                var ready by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    val start = System.currentTimeMillis()
                    withContext(Dispatchers.IO) { runCatching { vm.refresh() } } // sync + organize data
                    val elapsed = System.currentTimeMillis() - start
                    if (elapsed < SPLASH_MIN_MS) delay(SPLASH_MIN_MS - elapsed)  // keep the splash a few seconds
                    ready = true
                }
                if (ready) {
                    SleepApp(vm = vm, onOpenSettings = { vm.openUsageAccessSettings(this) })
                } else {
                    SplashScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check permission + refresh (e.g. after returning from Usage Access settings), off the main thread.
        vm.refreshInBackground()
    }

    private companion object {
        const val SPLASH_MIN_MS = 2500L
    }
}
