package cz.tomas.couriercontrol

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.tomas.couriercontrol.ui.CC
import cz.tomas.couriercontrol.ui.CourierTheme
import cz.tomas.couriercontrol.ui.MainScreen
import cz.tomas.couriercontrol.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ControlService.send(this, ControlService.ACTION_LOCATION_REFRESH)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.load(this)
        ControlService.send(this, ControlService.ACTION_START)
        requestPermissions()

        setContent {
            CourierTheme {
                val st by CourierState.ui.collectAsStateWithLifecycle()
                val settings by SettingsStore.state.collectAsStateWithLifecycle()
                var showSettings by remember { mutableStateOf(settings.obsHost.isBlank()) }
                BackHandler(enabled = showSettings) { showSettings = false }
                Box(Modifier.fillMaxSize().background(CC.Bg).safeDrawingPadding()) {
                    if (showSettings) {
                        SettingsScreen(
                            initial = settings,
                            st = st,
                            onSave = { SettingsStore.save(this@MainActivity, it) },
                            onBack = { showSettings = false },
                            onBatteryOptimization = { askIgnoreBatteryOptimizations() },
                        )
                    } else {
                        MainScreen(
                            st = st,
                            settings = settings,
                            onDrive = { ControlService.send(this@MainActivity, ControlService.ACTION_DRIVE) },
                            onPrivacy = { ControlService.send(this@MainActivity, ControlService.ACTION_EMERGENCY) },
                            onDelivery = { ControlService.send(this@MainActivity, ControlService.ACTION_DELIVERY_TOGGLE) },
                            onObsTile = { ControlService.send(this@MainActivity, ControlService.ACTION_RECONNECT) },
                            onGpsTile = { requestPermissions() },
                            onSettings = { showSettings = true },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Služba mohla být systémem ukončena – znovu ji nastartovat
        if (!CourierState.ui.value.serviceRunning) ControlService.send(this, ControlService.ACTION_START)
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    @SuppressLint("BatteryLife")
    private fun askIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } else {
            startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
    }
}
