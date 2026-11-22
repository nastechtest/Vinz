package com.vinz.music.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the phone can reach the internet right now, and over what. Offline, Vinz shows what it saved (playlists,
 * covers, downloads) instead of waiting on requests that can't go anywhere; on mobile data, data saver applies.
 */
class NetworkState(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _online = MutableStateFlow(read().first)
    /** A network with internet access (see [read]). */
    val online: StateFlow<Boolean> = _online
    private val _metered = MutableStateFlow(read().second)
    /** On mobile data (or another network that charges by use). */
    val metered: StateFlow<Boolean> = _metered

    /**
     * A network with internet that isn't stuck behind a sign-in page counts as online. Android's own check
     * (VALIDATED) isn't required: it fails on some networks that work fine, and Vinz would act offline there.
     */
    private fun read(): Pair<Boolean, Boolean> = of(cm?.getNetworkCapabilities(cm.activeNetwork))

    private fun of(caps: NetworkCapabilities?): Pair<Boolean, Boolean> {
        caps ?: return false to true
        return (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) to
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** [block] runs (on a background thread) each time the phone gets back online. */
    fun onReconnect(block: () -> Unit) { listeners += block }

    private val switch = NetworkSwitch()
    private val _changes = MutableStateFlow(0)
    /**
     * 1.8.7: goes up each time the phone moves to another network or comes back online (not for the first network
     * seen, nor for changes within one network). Playback lifts its blocks on songs that failed on the old one.
     */
    val changes: StateFlow<Int> = _changes

    init {
        // The default network's own capabilities, as the callback hands them over: activeNetwork can lag behind it (seen
        // after Android's system restarted: the phone was back on mobile data, Vinz stayed "offline").
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(of(caps), network)
                override fun onLost(network: Network) = update(false to true, null)
                override fun onAvailable(network: Network) = update(of(cm.getNetworkCapabilities(network)), network)
            })
        }
    }

    private fun update(state: Pair<Boolean, Boolean>, network: Network?) {
        if (synchronized(switch) { switch.seen(network, state.first) }) _changes.value++
        update(state)
    }

    private fun update(state: Pair<Boolean, Boolean>) {
        val (online, metered) = state
        val back = online && !_online.value
        _online.value = online
        _metered.value = metered
        if (back) listeners.forEach { runCatching { it() } }
    }

    /** True when it's worth trying the network (a fresh check, as the callback can lag a moment behind). */
    fun isOnline(): Boolean = read().first
}

/**
 * Tells a move to another network from the callbacks of one ([NetworkState.changes]): true when the phone is online now
 * and was offline before, or on another network. The first network seen only sets where it starts.
 */
internal class NetworkSwitch {
    private var known = false
    private var current: Any? = null

    fun seen(network: Any?, online: Boolean): Boolean {
        val was = current
        val wasKnown = known
        known = true
        current = if (online) network else null
        return online && wasKnown && was != network
    }
}
