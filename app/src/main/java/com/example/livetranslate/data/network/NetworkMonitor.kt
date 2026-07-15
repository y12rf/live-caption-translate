package com.example.livetranslate.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide connectivity observer. Used to park ASR/LLM when offline
 * and to auto-resume failed / disk-queued work when the network returns.
 */
class NetworkMonitor(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _online = MutableStateFlow(readOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
    }

    fun start() {
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(req, callback)
        } catch (_: Exception) {
            // Some OEMs throw; fall back to polling via refresh() from callers.
        }
        refresh()
    }

    fun stop() {
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    fun refresh() {
        _online.value = readOnline()
    }

    fun isOnline(): Boolean = _online.value

    private fun readOnline(): Boolean {
        return try {
            // minSdk 26: always use NetworkCapabilities (activeNetworkInfo is deprecated).
            val n = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(n) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                        // VALIDATED can lag on captive portals / weak links — accept transport alone
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    )
        } catch (_: Exception) {
            true // fail-open so we still attempt API calls
        }
    }
}
