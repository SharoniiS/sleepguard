package com.sleepguard.poc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.sleepguard.poc.ui.SleepApp
import com.sleepguard.poc.ui.SleepGuardTheme

/**
 * The product launcher — the Compose UI (4 tabs). The old View-based [MainActivity] is kept as a
 * debug screen (no longer the launcher) during the View→Compose migration.
 */
class AppActivity : ComponentActivity() {

    private val vm: SleepViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SleepGuardTheme {
                SleepApp(vm = vm, onOpenSettings = { vm.openUsageAccessSettings(this) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh() // re-check permission + auto-collect, incl. after returning from Settings
    }
}
