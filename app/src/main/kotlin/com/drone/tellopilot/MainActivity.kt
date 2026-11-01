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
import androidx.navigation.toRoute
import com.drone.tellopilot.ui.flights.FlightDetailRoute
import com.drone.tellopilot.ui.flights.FlightsRoute
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
        localNetworkRequestPending = false
        if (granted) {
            // onStart may already have reconnected (activity stopped while the dialog was shown).
            if (localNetworkMissing) {
                localNetworkMissing = false
                session.reconnect()
            }
        } else {
            Toast.makeText(this, R.string.local_network_denied, Toast.LENGTH_LONG).show()
        }
    }

    /** The system dialog is showing (kept across recreation: never ask twice at once). */
    private var localNetworkRequestPending = false

    /**
     * Checked on every start, not only on the first creation: the permission may have been
     * revoked (process restored with a saved state) or granted later from the system settings.
     */
    private fun checkLocalNetwork() {
        if (Build.VERSION.SDK_INT < LOCAL_NETWORK_API) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            // Granted from the settings while the sockets were failing with EPERM: reopen them.
            if (localNetworkMissing) {
                localNetworkMissing = false
                session.reconnect()
            }
        } else {
            localNetworkMissing = true
            if (!localNetworkRequestPending) {
                localNetworkRequestPending = true
                localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        checkLocalNetwork()
        if (!localNetworkRequestPending) askNotificationsOnce()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Notifications (progress of the post-flight re-encoding) are asked for once, never in
     * flight: refused, they can still be allowed from Settings › Recording and videos.
     */
    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false) || session.lastFlight?.flying == true) return
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ASKED, true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LOCAL_NETWORK_PENDING, localNetworkRequestPending)
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

    override fun onResume() {
        super.onResume()
        preferHighestRefreshRate()
    }

    /** Resolution change without recreation (split screen, display size): pick the mode again. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        preferHighestRefreshRate()
    }

    /**
     * The Pixel drops to 60 Hz when the content changes little (30 fps drone video): pinning the
     * highest refresh rate of the current resolution halves the touch sampling and display delay.
     * Only while the app is in the foreground.
     */
    private fun preferHighestRefreshRate() {
        // Activity.getDisplay(): valid from onCreate (the decor view has no display before its first traversal).
        val display = runCatching { display }.getOrNull() ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        if (window.attributes.preferredDisplayModeId != best.modeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
    }

    override fun onPause() {
        gamepad.release() // never keep a stick deflected in the background
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        localNetworkRequestPending = savedInstanceState?.getBoolean(KEY_LOCAL_NETWORK_PENDING) ?: false
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
                    composable<Flight> {
                        FlightRoute(
                            onOpenSettings = { nav.navigate(Settings) { launchSingleTop = true } },
                            onOpenGallery = { nav.navigate(Gallery) { launchSingleTop = true } },
                        )
                    }
                    composable<Gallery> {
                        com.drone.tellopilot.ui.gallery.GalleryRoute(
                            onBack = { nav.popBackStack() },
                            onOpenFlights = { nav.navigate(Flights) { launchSingleTop = true } },
                            onOpenTranscodes = { nav.navigate(Transcodes) { launchSingleTop = true } },
                        )
                    }
                    composable<Transcodes> {
                        com.drone.tellopilot.ui.gallery.TranscodeQueueRoute(onBack = { nav.popBackStack() })
                    }
                    // Takeoff from a flight log screen: straight back to the flight screen.
                    composable<Flights> {
                        FlightsRoute(
                            onBack = { nav.popBackStack() },
                            onOpen = { nav.navigate(FlightDetail(it)) { launchSingleTop = true } },
                            onFlying = { nav.popBackStack(Flight, inclusive = false) },
                        )
                    }
                    composable<FlightDetail> { entry ->
                        FlightDetailRoute(
                            id = entry.toRoute<FlightDetail>().id,
                            onBack = { nav.popBackStack() },
                            onFlying = { nav.popBackStack(Flight, inclusive = false) },
                        )
                    }
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
private data object Gallery

@Serializable
private data object Flights

@Serializable
private data object Transcodes

@Serializable
private data class FlightDetail(val id: String)

@Serializable
private data object Settings

@Serializable
private data object ImuCalibration

@Serializable
private data object GamepadConfig

/** First release enforcing the local network permission (Android 17). */
private const val LOCAL_NETWORK_API = 37
private const val KEY_LOCAL_NETWORK_PENDING = "local_network_pending"
        const val PREFS = "app"
        const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"

/** Seen missing in this process: once granted, the session must reopen its sockets. */
private var localNetworkMissing = false
