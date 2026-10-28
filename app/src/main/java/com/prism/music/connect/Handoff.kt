package com.prism.music.connect

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.prism.music.AppContainer
import com.prism.music.data.Notifications
import com.prism.music.playback.toSong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.coroutines.resume

/** Another Prism on this network that's available for handoff. */
data class PrismDevice(val name: String, val host: String, val port: Int, val id: String?, val key: String, val paired: Boolean)

/** A code this device is showing for another to type in, while it pairs. */
data class ShownCode(val code: String, val from: String)

/**
 * Prism Connect's handoff: what's playing moves from one Prism to another on the same Wi-Fi (phone to tablet, one phone
 * to another), queue, place, shuffle and all; or is brought over from the other one. A device that's "available for
 * handoff" (Settings → Prism Connect) listens and advertises itself as `_prismconnect._tcp`; any Prism can find it. The
 * first time two pair with a code shown on the receiving one; after that they recognise each other. Local network
 * only, no server, and its own protocol (Listen Together's is separate).
 */
class Handoff(private val app: Application, private val c: AppContainer) : HandoffLocal {
    private val prefs = app.getSharedPreferences("connect_handoff", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsd: NsdManager? by lazy { app.getSystemService(NsdManager::class.java) }
    private val peer = HandoffPeer(this)

    private val _devices = MutableStateFlow<List<PrismDevice>>(emptyList())
    val devices: StateFlow<List<PrismDevice>> = _devices.asStateFlow()
    private val _shown = MutableStateFlow<ShownCode?>(null)
    /** The pairing code this device is showing (in the app and as a notification). */
    val shownCode: StateFlow<ShownCode?> = _shown.asStateFlow()
    private val _asking = MutableStateFlow<String?>(null)
    /** The device whose code the listener is being asked for. */
    val askingCodeFor: StateFlow<String?> = _asking.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    /** "Moving to Pixel Tablet…" while a handoff is under way. */
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()
    private val _pairings = MutableStateFlow(pairings())
    /** Devices this one is paired with: id → name. */
    val pairedDevices: StateFlow<List<Pair<String, String>>> = _pairings.asStateFlow()

    override val device: String = prefs.getString("me", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("me", it).apply() }

    override val name: String
        get() {
            val label = runCatching { app.applicationInfo.loadLabel(app.packageManager).toString() }.getOrDefault("Vinz")
            val who = c.displayName(first = true)
            val base = if (who.isNotBlank()) "$who's ${Build.MODEL}" else Build.MODEL ?: "Vinz"
            // Two copies of Prism on one phone (a test copy beside the real one) need telling apart.
            return if (label != "Vinz") "$base ($label)" else base
        }

    // ------------------------------------------------------------ Pairings

    private fun pairings(): List<Pair<String, String>> = prefs.all.keys.filter { it.startsWith("s:") }.map { k ->
        val id = k.removePrefix("s:")
        id to (prefs.getString("n:$id", null) ?: "A Vinz device")
    }.sortedBy { it.second.lowercase() }

    override fun secretFor(device: String): String? = prefs.getString("s:$device", null)

    override fun savePairing(device: String, name: String, secret: String) {
        prefs.edit().putString("s:$device", secret).putString("n:$device", name).apply()
        _pairings.value = pairings()
        refreshPaired()
    }

    override fun forget(device: String) {
        prefs.edit().remove("s:$device").remove("n:$device").apply()
        _pairings.value = pairings()
        refreshPaired()
    }

    private fun refreshPaired() {
        _devices.value = _devices.value.map { it.copy(paired = it.id != null && secretFor(it.id) != null) }
    }

    override fun showCode(code: String?, from: String) {
        _shown.value = code?.let { ShownCode(it, from) }
        if (code == null) {
            runCatching { androidx.core.app.NotificationManagerCompat.from(app).cancel(Notifications.CONNECT, NOTIFY_ID) }
        } else {
            Notifications.post(
                app, Notifications.CONNECT, NOTIFY_ID, "Pairing code: ${code.take(3)} ${code.drop(3)}",
                "$from wants to play music on this device. Type this code on $from to pair them.", "prism://connect",
            ) { it.setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH).setTimeoutAfter(HandoffPeer.PAIR_WAIT_MS.toLong()) }
        }
    }

    /** Puts the shown code away (the other device's wait then simply runs out). */
    fun hideCode() {
        _shown.value = null
        runCatching { androidx.core.app.NotificationManagerCompat.from(app).cancel(Notifications.CONNECT, NOTIFY_ID) }
    }

    // ------------------------------------------------------------ The player

    override fun snapshot(): HandoffSnapshot? = runBlocking(Dispatchers.Main) {
        val p = c.player.player ?: return@runBlocking null
        if (p.mediaItemCount == 0) return@runBlocking null
        val songs = (0 until p.mediaItemCount).mapNotNull { p.getMediaItemAt(it).toSong() }
        if (songs.size != p.mediaItemCount) return@runBlocking null
        // The play order, read off the timeline (it covers every song once, shuffled or not).
        val order = if (!p.shuffleModeEnabled) emptyList() else buildList {
            val tl = p.currentTimeline
            var i = tl.getFirstWindowIndex(true)
            while (i != androidx.media3.common.C.INDEX_UNSET && size < tl.windowCount) {
                add(i); i = tl.getNextWindowIndex(i, androidx.media3.common.Player.REPEAT_MODE_OFF, true)
            }
        }
        HandoffSnapshot(
            songs = songs, index = p.currentMediaItemIndex, positionMs = p.currentPosition, playing = p.playWhenReady,
            shuffle = p.shuffleModeEnabled, order = order, repeat = p.repeatMode, title = c.queue.source.value.title,
        )
    }

    /**
     * Android (15 and later) won't let an app in the background start playing: its request for the speaker is ignored.
     * So music only moves here while Prism is open here, or already playing (it has the speaker then); otherwise the
     * other device is told so and keeps playing, rather than going quiet with nothing playing here either.
     */
    override fun takeOver(s: HandoffSnapshot, from: String): String? {
        val canPlay = runBlocking(Dispatchers.Main) { onScreen() || c.player.isPlaying.value }
        if (s.playing && !canPlay) {
            Log.i(TAG, "turned down a move from $from: not on screen or playing here")
            return "Open Vinz on $name first: Android only lets it start playing there while it's open (or already playing)"
        }
        runBlocking(Dispatchers.Main) {
            c.player.takeOver(s.songs, s.index, s.positionMs, s.playing, s.order.takeIf { s.shuffle }, s.repeat, s.title.ifBlank { "From $from" })
        }
        if (s.playing) {
            // Started for real (the speaker can still be refused): only then does the other device stop.
            val started = runBlocking { withTimeoutOrNull(START_WAIT_MS) { while (!c.player.isPlaying.value) delay(100); true } } == true
            if (!started) return "$name couldn't start playing. Open Vinz there and try again"
        }
        _events.tryEmit("Playing here from $from")
        return null
    }

    private fun onScreen(): Boolean {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    override fun stepAside() {
        runBlocking(Dispatchers.Main) {
            val p = c.player.player ?: return@runBlocking
            if (!p.playWhenReady) return@runBlocking
            // Faded out rather than cut (unless casting, where the phone's own player is silent anyway).
            if (!c.cast.casting) {
                val start = p.volume
                for (step in 1..12) { p.volume = start * (1f - step / 12f); delay(70) }
                p.pause()
                p.volume = start
            } else p.pause()
        }
    }

    // ------------------------------------------------------------ Being available

    @Volatile private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null

    fun setAvailable(on: Boolean) { if (on) startServer() else stopServer() }

    @Synchronized
    private fun startServer() {
        if (server != null) return
        val ss = runCatching { ServerSocket(PORT) }.getOrNull() ?: runCatching { ServerSocket(0) }.getOrNull() ?: return
        server = ss
        scope.launch {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                if (!Lan.isLocal(s.inetAddress)) { runCatching { s.close() }; continue }
                scope.launch { HLink(s.apply { tcpNoDelay = true }).use { link -> runCatching { peer.serve(link) }.onFailure { Log.w(TAG, "handoff from ${link.remote} failed", it) } } }
            }
        }
        register(ss.localPort)
        Log.i(TAG, "available for handoff on ${ss.localPort} as $name")
    }

    @Synchronized
    private fun stopServer() {
        registration?.let { l -> runCatching { nsd?.unregisterService(l) } }
        registration = null
        runCatching { server?.close() }
        server = null
    }

    private fun register(port: Int) {
        val manager = nsd ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "$NAME_PREFIX${name.take(40)}"
            serviceType = SERVICE
            setPort(port)
            setAttribute("id", device)
            setAttribute("v", VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Log.w(TAG, "advertising failed: $error") }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { registration = null }
    }

    // ------------------------------------------------------------ Finding others

    private var discovery: NsdManager.DiscoveryListener? = null
    private var lookers = 0
    private val resolveLock = Mutex()

    fun startDiscovery() {
        lookers++
        val manager = nsd ?: return
        if (discovery != null) return
        _devices.value = emptyList()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { if (info.serviceType.contains(SERVICE_LABEL)) resolve(info) }
            override fun onServiceLost(info: NsdServiceInfo) { _devices.value = _devices.value.filterNot { it.key == info.serviceName } }
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) { discovery = null }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
        }
        discovery = listener
        runCatching { manager.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { discovery = null }
    }

    fun stopDiscovery() {
        lookers = (lookers - 1).coerceAtLeast(0)
        if (lookers > 0) return
        discovery?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        discovery = null
    }

    private fun resolve(info: NsdServiceInfo) {
        val manager = nsd ?: return
        scope.launch {
            resolveLock.withLock {
                val r = withTimeoutOrNull(6_000) {
                    suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                        runCatching {
                            @Suppress("DEPRECATION")
                            manager.resolveService(info, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(i: NsdServiceInfo, error: Int) { if (cont.isActive) cont.resume(null) }
                                override fun onServiceResolved(i: NsdServiceInfo) { if (cont.isActive) cont.resume(i) }
                            })
                        }.onFailure { if (cont.isActive) cont.resume(null) }
                    }
                } ?: return@withLock
                val id = r.attributes["id"]?.toString(Charsets.UTF_8)
                // Itself (this very app, advertising while it looks).
                if (id == device) return@withLock
                @Suppress("DEPRECATION")
                val all = if (Build.VERSION.SDK_INT >= 34) r.hostAddresses else listOfNotNull(r.host)
                val host = (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())?.hostAddress ?: return@withLock
                val d = PrismDevice(r.serviceName.removePrefix(NAME_PREFIX), host, r.port, id, r.serviceName, id != null && secretFor(id) != null)
                _devices.value = (_devices.value.filterNot { it.key == d.key } + d).sortedBy { it.name.lowercase() }
            }
        }
    }

    // ------------------------------------------------------------ Moving

    private var code: CompletableDeferred<String?>? = null

    /** The code the listener typed for [askingCodeFor] (null: cancelled). */
    fun submitCode(value: String?) {
        code?.complete(value?.filter { it.isDigit() }?.takeIf { it.length == 6 })
    }

    /** What's playing here goes to [d]. */
    fun moveTo(d: PrismDevice) = run(d, HandoffCommand.MOVE, "Moving to ${d.name}…")

    /** What's playing on [d] comes here. */
    fun bringFrom(d: PrismDevice) = run(d, HandoffCommand.PULL, "Bringing it over from ${d.name}…")

    private fun run(d: PrismDevice, command: HandoffCommand, label: String) {
        if (_busy.value != null) return
        _busy.value = label
        scope.launch {
            try {
                var result = attempt(d, command)
                // Its key was refused (forgotten over there): pair again, once.
                if (result == HandoffResult.Unpaired) result = attempt(d, command)
                when (result) {
                    is HandoffResult.Done -> _events.tryEmit(
                        (if (command == HandoffCommand.MOVE) "Playing on ${d.name}" else "Playing here") + (result.note?.let { " · $it" } ?: "")
                    )
                    is HandoffResult.Failed -> _events.tryEmit(result.reason)
                    HandoffResult.Unpaired -> _events.tryEmit("${d.name} didn't accept the pairing. Try again.")
                    HandoffResult.Cancelled -> Unit
                }
            } finally {
                _busy.value = null
                _asking.value = null
            }
        }
    }

    private fun attempt(d: PrismDevice, command: HandoffCommand): HandoffResult {
        val socket = runCatching { Socket().apply { connect(InetSocketAddress(d.host, d.port), 5_000); tcpNoDelay = true } }.getOrNull()
            ?: return HandoffResult.Failed("Couldn't reach ${d.name}. Is Vinz open there, on the same Wi-Fi?")
        return HLink(socket).use { link ->
            runCatching {
                peer.call(link, command) { them ->
                    val wait = CompletableDeferred<String?>()
                    code = wait
                    _asking.value = them
                    try { runBlocking { withTimeoutOrNull(HandoffPeer.PAIR_WAIT_MS.toLong() - 5_000) { wait.await() } } } finally { _asking.value = null; code = null }
                }
            }.getOrElse { HandoffResult.Failed("Lost the connection to ${d.name}") }
        }
    }

    companion object {
        private const val TAG = "PrismHandoff"
        const val VERSION = 1
        const val PORT = 47321
        private const val SERVICE_LABEL = "_prismconnect"
        private const val SERVICE = "$SERVICE_LABEL._tcp"
        private const val NAME_PREFIX = "Vinz · "
        private const val NOTIFY_ID = 4701
        /** How long a song gets to start here (it may still be loading) before the move counts as failed. */
        private const val START_WAIT_MS = 12_000L
    }
}
