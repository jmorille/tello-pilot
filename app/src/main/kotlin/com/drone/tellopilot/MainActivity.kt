package com.drone.tellopilot

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.drone.tellopilot.data.DroneSession
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
import com.drone.tellopilot.ui.settings.ImuCalibrationRoute
import com.drone.tellopilot.ui.settings.GamepadRoute
import com.drone.tellopilot.input.GamepadInput
import android.view.KeyEvent
import android.view.MotionEvent
import javax.inject.Inject
import kotlinx.serialization.Serializable
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var gamepad: GamepadInput
    @Inject lateinit var session: DroneSession

    /**
     * Android 17 blocks traffic to the local network (EPERM on every send) for apps targeting
     * API 37 until ACCESS_LOCAL_NETWORK is granted: ask for it, then reopen the sockets.
     */
    private val localNetworkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            session.reconnect()
        } else {
            Toast.makeText(this, R.string.local_network_denied, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestLocalNetworkIfNeeded() {
        if (Build.VERSION.SDK_INT < LOCAL_NETWORK_API) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }

    // Gamepad events are handled before Compose sees them (sticks never wait for a frame).
    // Platform API (Activity.dispatchKeyEvent); lint flags it only because androidx.core's
    // ComponentActivity also overrides it. Needed: Compose focus would otherwise eat the D-pad.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = gamepad.onKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        gamepad.onMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    /** Notification shade, system dialog, split-screen focus…: re-centre the pad sticks. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) gamepad.release()
    }

    override fun onPause() {
        gamepad.release() // never keep a stick deflected in the background
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) requestLocalNetworkIfNeeded()
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
                    composable<Settings> {
                        SettingsRoute(
                            onBack = { nav.popBackStack() },
                            onOpenImuCalibration = { nav.navigate(ImuCalibration) { launchSingleTop = true } },
                            onOpenGamepad = { nav.navigate(GamepadConfig) { launchSingleTop = true } },
                        )
                    }
                    composable<GamepadConfig> { GamepadRoute(onBack = { nav.popBackStack() }) }
                    composable<ImuCalibration> { ImuCalibrationRoute(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }
}

@Serializable
private data object Flight

@Serializable
private data object Settings

@Serializable
private data object ImuCalibration

@Serializable
private data object GamepadConfig

/** First release enforcing the local network permission (Android 17). */
private const val LOCAL_NETWORK_API = 37
