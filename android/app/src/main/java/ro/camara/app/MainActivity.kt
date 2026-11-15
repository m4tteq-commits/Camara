package ro.camara.app

import android.content.Intent
import android.os.Bundle
import android.net.ConnectivityManager
import android.net.Network
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ro.camara.app.data.CamaraApi
import ro.camara.app.data.RecipeWebImport
import ro.camara.app.ui.CamaraApp
import ro.camara.app.ui.CamaraTheme
import ro.camara.app.ui.AppearanceStore

class MainActivity : ComponentActivity() {
    private val model: CamaraViewModel by viewModels()
    private val aiPresenceOwner = Any()
    private var sharedLink by mutableStateOf<String?>(null)

    private fun acceptShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        sharedLink = RecipeWebImport.sharedLink(text)
        if (sharedLink == null) model.message("Distribuie un link către o rețetă din browser.")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptShare(intent)
    }

    override fun onStart() {
        super.onStart()
        (application as CamaraApplication).serverAiPresence.setVisible(aiPresenceOwner, true)
    }

    override fun onStop() {
        (application as CamaraApplication).serverAiPresence.setVisible(aiPresenceOwner, false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) acceptShare(intent)
        enableEdgeToEdge()
        val appearanceStore = AppearanceStore(this)
        setContent {
            var appearance by remember { mutableStateOf(appearanceStore.read()) }
            val dark = appearance.dark(isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            CamaraTheme(dark, appearance.palette, appearance.motion) {
                CamaraApp(model, appearance, sharedLink = sharedLink, onShareHandled = { sharedLink = null }) { updated ->
                    appearanceStore.save(updated)
                    appearance = updated
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    val connectivity = getSystemService(ConnectivityManager::class.java)
                    connectivity.defaultNetworks().collectLatest { network ->
                        CamaraApi.networkChanged(network?.networkHandle)
                        if (network == null) {
                            model.networkUnavailable()
                        } else {
                            delay(350)
                            snapshotFlow { !model.state.busy && !model.state.generating && !model.state.syncing }.first { it }
                            model.refresh(silent = true)
                        }
                    }
                }
                launch {
                    var seconds = 30L
                    while (true) {
                        delay(seconds * 1_000)
                        model.refresh(silent = true)
                        snapshotFlow { !model.state.syncing }.first { it }
                        seconds = if (model.state.online) 30L else (seconds * 2).coerceAtMost(300L)
                    }
                }
            }
        }
    }
}

private fun ConnectivityManager.defaultNetworks() = callbackFlow {
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(Unit) }
        override fun onLost(network: Network) { trySend(Unit) }
    }
    registerDefaultNetworkCallback(callback)
    trySend(Unit)
    awaitClose { unregisterNetworkCallback(callback) }
}.map { activeNetwork }.distinctUntilChanged()
