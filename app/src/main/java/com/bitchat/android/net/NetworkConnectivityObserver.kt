package com.bitchat.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Modern Android observer for internet connectivity status.
 */
class NetworkConnectivityObserver(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Observable stream of connectivity status.
     */
    val status: Flow<Status> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                launch { send(Status.Online) }
            }

            override fun onLosing(network: Network, maxMs: Int) {
                super.onLosing(network, maxMs)
                launch { send(Status.Offline) }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                launch { send(Status.Offline) }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                launch { send(Status.Offline) }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        connectivityManager.registerNetworkCallback(request, callback)

        // Set initial state
        val isCurrentlyOnline = connectivityManager.activeNetwork?.let { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: false
        
        launch { send(if (isCurrentlyOnline) Status.Online else Status.Offline) }

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    enum class Status {
        Online, Offline
    }
}
