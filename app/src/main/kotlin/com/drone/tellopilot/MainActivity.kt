package com.drone.tellopilot

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.drone.tellopilot.ui.flight.FlightRoute
import com.drone.tellopilot.ui.settings.SettingsRoute
import kotlinx.serialization.Serializable
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val nav = rememberNavController()
                // The flight destination stays on the back stack while settings are open:
                // its ViewModel (and the drone session) keeps running.
                NavHost(nav, startDestination = Flight) {
                    composable<Flight> { FlightRoute(onOpenSettings = { nav.navigate(Settings) { launchSingleTop = true } }) }
                    composable<Settings> { SettingsRoute(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }
}

@Serializable
private data object Flight

@Serializable
private data object Settings
