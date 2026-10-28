package com.prism.music.playback

import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.prism.music.AppContainer
import com.prism.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

data class QueueEntry(val index: Int, val song: Song, val uid: String)

/** Outputs that take over when they connect (not microphones, call channels or the phone's own parts). */
private val NEW_OUTPUTS = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_DOCK,
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
)

sealed interface VideoSyncState {
    data object Off : VideoSyncState
    data object Measuring : VideoSyncState
    /** Video time minus song time. */
    data class Synced(val lagMs: Long) : VideoSyncState
    data object Unmatched : VideoSyncState
}

/**
 * UI-facing handle on playback. The service runs in-process, so once bound we
 * drive its ExoPlayer directly and mirror its state into flows for Compose.
 */
class PlayerConnection(private val context: Context, private val c: AppContainer) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var service: PlaybackService? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val pending = mutableListOf<(ExoPlayer) -> Unit>()

    val player: ExoPlayer? get() = service?.player

    private val _current = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _current
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering
    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue
    private val _index = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _index
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle
    /** Queue indices in the order they'll actually play after the current one (follows shuffle). */
    private val _upNext = MutableStateFlow<List<Int>>(emptyList())
    val upNext: StateFlow<List<Int>> = _upNext
    private val _played = MutableStateFlow<List<Int>>(emptyList())
    /** Queue indices already played (before the current song in play order), oldest first. */
    val played: StateFlow<List<Int>> = _played
    private val _repeat = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeat
    private val _videoMode = MutableStateFlow(false)
    val videoMode: StateFlow<Boolean> = _videoMode
    private val _videoAvailable = MutableStateFlow(false)
    val videoAvailable: StateFlow<Boolean> = _videoAvailable
    private val _hasNext = MutableStateFlow(false)
    val hasNext: StateFlow<Boolean> = _hasNext
    private val _hasPrevious = MutableStateFlow(false)
    val hasPrevious: StateFlow<Boolean> = _hasPrevious
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors = _errors.asSharedFlow()
    private val _sleepAt = MutableStateFlow(0L)
    val sleepAt: StateFlow<Long> = _sleepAt
    private val _output = MutableStateFlow<AudioDeviceInfo?>(null)
    /** The output picked in Prism's device sheet; null follows the system (e.g. newly connected headphones). */
    val preferredOutput: StateFlow<AudioDeviceInfo?> = _output

    init {
        context.getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            private var primed = false
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (primed) AudioDiagnostics.log("device", "added: ${addedDevices.joinToString { AudioDiagnostics.describe(it) }}")
                // The callback reports existing devices once on registration; after that, a new output (headphones
                // plugged in, earbuds connected) takes over like it would elsewhere, which following the system does by
                // itself. So only an output the listener picked is let go, and only for a real new output: earbuds also
                // add their microphone and call channels, and AirPods add and drop entries with ear detection and the
                // case. Each of those used to re-point the player at "follow the system" (when it already was), and on
                // Bluetooth every such re-route is a moment's gap.
                if (primed && _output.value != null && addedDevices.any { it.isSink && it.type in NEW_OUTPUTS }) {
                    setOutput(null, "a new output was connected")
                }
                primed = true
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                AudioDiagnostics.log("device", "removed: ${removedDevices.joinToString { AudioDiagnostics.describe(it) }}")
                if (removedDevices.any { it.id == _output.value?.id }) setOutput(null, "the picked output went away")
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    // ------------------------------------------------------------ Lyric timing

    private val _videoSync = MutableStateFlow<VideoSyncState>(VideoSyncState.Off)
    /** How the lyrics are lined up with the current music video. */
    val videoSync: StateFlow<VideoSyncState> = _videoSync
    private val _lyricOffset = MutableStateFlow(0L)
    /** The listener's own nudge for this song (and mode), in ms; positive shows lyrics earlier. */
    val lyricOffset: StateFlow<Long> = _lyricOffset
    private val _lyricShift = MutableStateFlow(0L)
    /** Added to the playhead to get the lyrics' time (the offset, minus any video lag). */
    val lyricShift: StateFlow<Long> = _lyricShift
    private var syncKey: String? = null
    private var syncJob: kotlinx.coroutines.Job? = null

    /** The playhead in the song's (lyrics') timeline. */
    val lyricPosition: Long get() = position + _lyricShift.value

    fun seekToLyric(ms: Long) = seekTo((ms - _lyricShift.value).coerceAtLeast(0))

    private fun offsetKey(): String? {
        val song = _current.value ?: return null
        return song.id + if (player?.currentMediaItem?.videoModeId != null) ":video" else ""
    }

    fun setLyricOffset(ms: Long) {
        val key = offsetKey() ?: return
        c.lyricOffsets.edit().apply { if (ms == 0L) remove(key) else putLong(key, ms) }.apply()
        _lyricOffset.value = ms
        updateShift()
    }

    private fun updateLyricTiming() {
        val song = _current.value
        val vid = player?.currentMediaItem?.videoModeId
        _lyricOffset.value = offsetKey()?.let { c.lyricOffsets.getLong(it, 0L) } ?: 0L
        val key = if (song != null && vid != null && vid != song.id) "${song.id}|$vid" else null
        if (key != syncKey) {
            syncKey = key
            syncJob?.cancel()
            if (key == null || song == null || vid == null) _videoSync.value = VideoSyncState.Off
            else {
                val known = c.videoSync.cached(song.id, vid)
                if (known != null) _videoSync.value = VideoSyncState.Synced(known)
                else {
                    _videoSync.value = VideoSyncState.Measuring
                    syncJob = scope.launch {
                        val lag = c.videoSync.lagMs(song.id, vid)
                        if (syncKey == key) {
                            _videoSync.value = lag?.let { VideoSyncState.Synced(it) } ?: VideoSyncState.Unmatched
                            updateShift()
                        }
                    }
                }
            }
        }
        updateShift()
    }

    /** Measures the current music video against the song again. */
    fun resyncVideo() {
        val song = _current.value ?: return
        val vid = player?.currentMediaItem?.videoModeId ?: return
        c.videoSync.forget(song.id, vid)
        syncKey = null
        updateLyricTiming()
    }

    private fun updateShift() {
        val lag = (_videoSync.value as? VideoSyncState.Synced)?.lagMs ?: 0L
        _lyricShift.value = _lyricOffset.value - lag
    }

    private val _episodeSpeed = MutableStateFlow(1f)
    /** Podcast episodes' playback speed (music always plays at 1×). */
    val episodeSpeed: StateFlow<Float> = _episodeSpeed

    fun setEpisodeSpeed(speed: Float) {
        _episodeSpeed.value = speed
        service?.episodeSpeed = speed
    }

    private val _musicSpeed = MutableStateFlow(1f)
    /** Songs' playback speed (1.7.4, the Speed button under the player; 1× unless picked, for this session). */
    val musicSpeed: StateFlow<Float> = _musicSpeed

    fun setMusicSpeed(speed: Float) {
        _musicSpeed.value = speed
        service?.musicSpeed = speed
    }

    fun setOutput(device: AudioDeviceInfo?, reason: String = "picked in the device sheet") {
        // Picking one of the phone's own outputs while casting brings the music back here, playing on.
        if (device != null && c.cast.casting) c.cast.disconnect(keepPlaying = true)
        // Already there: nothing to re-route (a re-route is a gap on Bluetooth).
        if (device?.id == _output.value?.id) return
        AudioDiagnostics.log("route", "player pointed at ${AudioDiagnostics.describe(device)} ($reason; was ${AudioDiagnostics.describe(_output.value)})")
        _output.value = device
        player?.setPreferredAudioDevice(device)
    }

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackServices.target(context)))
        controllerFuture = MediaController.Builder(context, token).buildAsync()
    }

    internal fun attach(s: PlaybackService) {
        service = s
        s.episodeSpeed = _episodeSpeed.value
        s.musicSpeed = _musicSpeed.value
        s.player.addListener(this)
        refresh()
        pending.toList().forEach { it(s.player) }
        pending.clear()
    }

    internal fun detach() {
        service?.player?.removeListener(this)
        service = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    /**
     * Starts the playback service (not just binds to it) so it keeps running as
     * a media service — with its notification and system media controls — after
     * the app leaves the screen.
     */
    private fun ensureStarted() {
        runCatching { context.startService(android.content.Intent(context, PlaybackServices.target(context))) }
    }

    private fun withPlayer(block: (ExoPlayer) -> Unit) {
        val p = player
        if (p != null) block(p) else {
            pending += block
            connect()
        }
    }

    fun reportError(msg: String) {
        _errors.tryEmit(msg)
    }

    // ------------------------------------------------------------ Commands

    private val _showPlayer = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Asks the UI to bring the full player up. */
    val showPlayer = _showPlayer.asSharedFlow()

    /** Songs found for music videos / live performances this session (video id -> song). */
    private val videoSongs = java.util.concurrent.ConcurrentHashMap<String, Song>()
    private var playToken = 0

    fun playQueue(songs: List<Song>, startIndex: Int = 0, source: QueueSource, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        val token = ++playToken
        val first = if (shuffle) songs.indices.random() else startIndex.coerceIn(0, songs.lastIndex)
        val tapped = songs[first]
        val everywhere = songsEverywhere()
        // Live streams play as they are: they have no "song version" to swap in.
        if ((shuffle && !everywhere) || !tapped.isVideo || tapped.isLive || tapped.isEpisode) { startQueue(songs, first, source, shuffle, null); return }
        // With the Song / Video switch off the player is audio only (#48): a music video plays as its song, or as
        // the video's sound when it has no song, never with its picture (there'd be no switch to turn it off).
        // "Songs, not videos" everywhere (#80) plays it as its song too, and so does a shuffled start.
        val audioOnly = !c.settings.current.showVideoSwitch || everywhere || shuffle
        // A music video or live performance opens as the song it's of, already showing the video
        // (the Song button then plays the track itself). With no song to match, the video plays as it is.
        if (!audioOnly) _showPlayer.tryEmit(Unit)
        scope.launch {
            val song = songFor(tapped, 4_000)
            if (token != playToken) return@launch
            val list = if (song == null) songs else songs.toMutableList().also { it[first] = song }
            startQueue(list, first, source, shuffle, tapped.id.takeUnless { audioOnly })
        }
    }

    /** Settings → Playback → "Songs, not videos" → Everywhere (#80). */
    fun songsEverywhere(): Boolean = c.settings.current.let { it.radioSongsOnly && it.songsEverywhere }

    /**
     * The song a music video is of ([YouTubeMusic.songForVideo]), remembered for the session; the video becomes the
     * song's counterpart (its Video button). Null when there's none, or it took longer than [timeoutMs].
     */
    suspend fun songFor(video: Song, timeoutMs: Long = 8_000): Song? =
        (videoSongs[video.id] ?: kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { c.ytm.songForVideo(video) } }.getOrNull()
        }?.also { videoSongs[video.id] = it })?.also { c.queue.counterparts[it.id] = video.id }

    private fun startQueue(songs: List<Song>, startIndex: Int, source: QueueSource, shuffle: Boolean, startVideo: String?, startMs: Long = 0) {
        // DJ mode is for the queue it was turned on in; "Play as a DJ set" turns it on for the new one.
        val dj = djNext && !shuffle
        djNext = false
        c.dj.setOn(false)
        // A new queue starts as plain shuffle (or none); Smart Shuffle is turned on per queue.
        c.queue.smartShuffle.value = false
        c.queue.smartPicks.value = emptySet()
        c.queue.source.value = source
        c.queue.autoplayContinuation = null
        c.queue.autoplayStart.value = -1
        c.queue.userQueued.value = emptySet()
        ensureStarted()
        withPlayer { p ->
            p.shuffleModeEnabled = shuffle
            val start = startIndex.coerceIn(0, songs.lastIndex)
            p.setMediaItems(songs.mapIndexed { i, s -> s.toMediaItem(if (i == start) startVideo else null) }, start, startMs.coerceAtLeast(0))
            if (shuffle) reshuffle(p)
            p.prepare()
            p.play()
            service?.maybeAutoplay()
            if (dj) c.dj.setOn(true)
        }
    }

    private var djNext = false

    /** Plays [songs] as a DJ set (DJ mode on): from the first, then in the order that blends best (album/playlist menus). */
    fun playDjSet(songs: List<Song>, source: QueueSource) {
        djNext = true
        playQueue(songs, 0, source)
    }

    /** DJ mode on or off for the queue playing (the queue's switch). */
    fun setDjMode(on: Boolean) = withPlayer { p ->
        if (on && p.shuffleModeEnabled) p.shuffleModeEnabled = false
        c.dj.setOn(on)
    }

    /** Plays a lone song; autoplay then continues with similar music. */
    fun playSingle(song: Song) = playQueue(listOf(song), 0, QueueSource(QueueKind.SINGLE, "Autoplay", seedId = song.id))

    /**
     * Plays [song] from its best part (its most replayed stretch, or its chorus), then on as [playSingle] would.
     * Without one it plays from the start, and says so.
     */
    fun playFromBestPart(song: Song) {
        val token = ++playToken
        scope.launch {
            val start = kotlinx.coroutines.withTimeoutOrNull(5_000) { runCatching { c.bestParts.startMs(song) }.getOrNull() }
            if (token != playToken) return@launch
            if (start == null) reportError("Vinz doesn't know a best part for \"${song.title}\" yet, so it plays from the start")
            startQueue(listOf(song), 0, QueueSource(QueueKind.SINGLE, "Autoplay", seedId = song.id), shuffle = false, startVideo = null, startMs = start ?: 0)
        }
    }

    /** Plays [song] and then a radio of songs like it (YouTube Music's), whether or not Autoplay is on. */
    fun playRadio(song: Song) = playQueue(listOf(song), 0, QueueSource(QueueKind.RADIO, "${song.title} Radio", seedId = song.id))

    fun playNext(song: Song) = playNext(listOf(song), null)

    fun addToQueue(song: Song) = addToQueue(listOf(song), null)

    /** Songs the listener adds go in at [at]; autoplay's suggestions (after them) move along. */
    private fun insertQueued(p: ExoPlayer, at: Int, songs: List<Song>) {
        p.addMediaItems(at, songs.map { it.toMediaItem() })
        val auto = c.queue.autoplayStart.value
        if (auto >= at) c.queue.autoplayStart.value = auto + songs.size
        c.queue.userQueued.value = c.queue.userQueued.value + songs.map { it.id }
    }

    /** Songs (one, or a whole album or playlist) straight after the current song, or played, with nothing on. */
    fun playNext(songs: List<Song>, source: QueueSource?) {
        if (songs.isEmpty()) return
        withPlayer { p ->
            if (p.mediaItemCount == 0) {
                if (source == null) playSingle(songs.first()) else playQueue(songs, 0, source)
            } else insertQueued(p, p.currentMediaItemIndex + 1, songs)
        }
    }

    /**
     * Songs after what's queued, before autoplay's suggestions (they used to land after those, so a song added
     * to the queue could be a long way off). Played, with nothing on.
     */
    fun addToQueue(songs: List<Song>, source: QueueSource?) {
        if (songs.isEmpty()) return
        withPlayer { p ->
            if (p.mediaItemCount == 0) {
                if (source == null) playSingle(songs.first()) else playQueue(songs, 0, source)
            } else {
                val auto = c.queue.autoplayStart.value
                val cur = p.currentMediaItemIndex
                val at = when {
                    auto < 0 -> p.mediaItemCount
                    auto > cur -> minOf(auto, p.mediaItemCount)
                    // Already into autoplay's songs: after anything else queued, before the next suggestion.
                    else -> {
                        var i = cur + 1
                        while (i < p.mediaItemCount && p.getMediaItemAt(i).mediaId in c.queue.userQueued.value) i++
                        i
                    }
                }
                insertQueued(p, at, songs)
            }
        }
    }

    /** Listen Together: plays the host's queue ([songs], the current one first) from [positionMs], as songs, in order. */
    fun follow(songs: List<Song>, positionMs: Long, title: String, playing: Boolean) {
        if (songs.isEmpty()) return
        ++playToken
        c.queue.source.value = QueueSource(QueueKind.OTHER, title)
        c.queue.autoplayContinuation = null
        c.queue.autoplayStart.value = -1
        c.queue.userQueued.value = emptySet()
        ensureStarted()
        withPlayer { p ->
            p.shuffleModeEnabled = false
            p.repeatMode = Player.REPEAT_MODE_OFF
            p.setMediaItems(songs.map { it.toMediaItem() }, 0, positionMs.coerceAtLeast(0))
            p.prepare()
            p.playWhenReady = playing
        }
    }

    /**
     * Prism Connect handoff: another device's playback continues here, the whole queue from the same song and place,
     * in the same play order ([order]: queue indices as they play; null when not shuffled) and repeat mode.
     */
    fun takeOver(songs: List<Song>, index: Int, positionMs: Long, playing: Boolean, order: List<Int>?, repeat: Int, title: String) {
        if (songs.isEmpty()) return
        ++playToken
        djNext = false
        c.dj.setOn(false)
        c.queue.smartShuffle.value = false
        c.queue.smartPicks.value = emptySet()
        c.queue.source.value = QueueSource(QueueKind.OTHER, title)
        c.queue.autoplayContinuation = null
        c.queue.autoplayStart.value = -1
        c.queue.userQueued.value = emptySet()
        ensureStarted()
        withPlayer { p ->
            val start = index.coerceIn(0, songs.lastIndex)
            p.setMediaItems(songs.map { it.toMediaItem() }, start, positionMs.coerceAtLeast(0))
            p.repeatMode = repeat.coerceIn(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL)
            val shuffled = order != null && order.size == songs.size && order.sorted() == songs.indices.toList()
            if (shuffled) p.setShuffleOrder(HistoryShuffleOrder(order!!.toIntArray()) { p.currentMediaItemIndex })
            p.shuffleModeEnabled = shuffled
            p.prepare()
            p.playWhenReady = playing
            service?.maybeAutoplay()
        }
    }

    /** Listen Together: makes what plays after the current song match the host's. */
    fun replaceUpcoming(songs: List<Song>) = withPlayer { p ->
        val idx = p.currentMediaItemIndex
        val have = (idx + 1 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
        if (have == songs.map { it.id }) return@withPlayer
        if (idx + 1 < p.mediaItemCount) p.removeMediaItems(idx + 1, p.mediaItemCount)
        p.addMediaItems(songs.map { it.toMediaItem() })
    }

    /** The listener wants it playing (it may still be buffering). */
    val playWhenReady: Boolean get() = player?.playWhenReady ?: false

    fun setPlaying(playing: Boolean) = withPlayer { p ->
        if (playing) {
            ensureStarted()
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        } else p.pause()
    }

    /** Takes everything after the current song out of the queue. */
    fun clearUpcoming() = withPlayer { p ->
        val i = p.currentMediaItemIndex
        if (i + 1 < p.mediaItemCount) p.removeMediaItems(i + 1, p.mediaItemCount)
        c.queue.autoplayStart.value = -1
        c.queue.userQueued.value = emptySet()
    }

    /** Stops and empties the queue, so the mini player goes (#59: swiped down). */
    fun stop() = withPlayer { p ->
        p.stop()
        p.clearMediaItems()
        c.queue.autoplayStart.value = -1
        c.queue.autoplayContinuation = null
        c.queue.userQueued.value = emptySet()
    }

    fun togglePlay() = withPlayer { p ->
        if (!p.isPlaying) ensureStarted()
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0, 0)
        if (p.isPlaying) p.pause() else p.play()
    }

    fun next() = withPlayer { it.seekToNext() }
    fun previous() = withPlayer { it.seekToPrevious() }

    /** Calls [done] once the player is reached (after commands asked for before it, such as a widget's), at once if it is. */
    fun afterPending(done: () -> Unit) = withPlayer { done() }
    fun seekTo(ms: Long) = withPlayer { it.seekTo(ms) }
    /** Moves the playhead by [ms] (negative goes back), kept inside the song. */
    fun seekBy(ms: Long) = withPlayer { p ->
        val end = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + ms).coerceIn(0, (end - 1_000).coerceAtLeast(0)))
    }
    fun skipTo(index: Int) = withPlayer { p ->
        // Shuffled, the song picked plays next in the order, so Previous comes back to the one playing now.
        if (p.shuffleModeEnabled) (p.shuffleOrder as? HistoryShuffleOrder)?.let { p.setShuffleOrder(it.playingNext(p.currentMediaItemIndex, index)) }
        p.seekToDefaultPosition(index)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
    }
    fun removeAt(index: Int) = withPlayer {
        it.removeMediaItem(index)
        val auto = c.queue.autoplayStart.value
        if (auto > index) c.queue.autoplayStart.value = auto - 1
    }

    /**
     * Takes the song at [index] out (a swipe in the queue, #149) and returns how to put it back where it was, or null
     * when there's nothing there. The item itself goes back, so it keeps everything it was queued with.
     */
    fun removeForUndo(index: Int): (() -> Unit)? {
        val p = player ?: return null
        if (index !in 0 until p.mediaItemCount) return null
        val item = p.getMediaItemAt(index)
        val wasAuto = c.queue.autoplayStart.value.let { it in 0..index }
        removeAt(index)
        return {
            withPlayer { q ->
                val at = index.coerceAtMost(q.mediaItemCount)
                q.addMediaItem(at, item)
                val auto = c.queue.autoplayStart.value
                // Back among the songs it was with: before autoplay's suggestions, or one of them.
                if (auto >= 0 && (auto > at || auto == at && !wasAuto)) c.queue.autoplayStart.value = auto + 1
            }
        }
    }
    fun move(from: Int, to: Int) = withPlayer { it.moveMediaItem(from, to) }

    /**
     * Shuffled, a song dragged in the queue: the queue shows the play order then, so the song moves there, to just after
     * (or before) [beside], and the songs themselves stay where they are in the list behind it.
     */
    fun moveInPlayOrder(index: Int, beside: Int, after: Boolean) = withPlayer { p ->
        if (!p.shuffleModeEnabled) return@withPlayer
        val order = p.shuffleOrder as? HistoryShuffleOrder ?: HistoryShuffleOrder(playOrder(p).toIntArray()) { p.currentMediaItemIndex }
        p.setShuffleOrder(order.placed(index, beside, after))
    }

    /** The queue's indices in the order they'll play (shuffled or not). */
    private fun playOrder(p: ExoPlayer): List<Int> {
        val t = p.currentTimeline
        if (t.isEmpty) return emptyList()
        val out = mutableListOf<Int>()
        var i = t.getFirstWindowIndex(p.shuffleModeEnabled)
        while (i != C.INDEX_UNSET && out.size < t.windowCount) {
            out += i
            i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
        }
        return out
    }
    /** Shuffle's button: off → shuffle → Smart Shuffle (where the queue allows it) → off. */
    fun toggleShuffle() = withPlayer {
        when {
            !it.shuffleModeEnabled -> { reshuffle(it); it.shuffleModeEnabled = true }
            !c.queue.smartShuffle.value && c.queue.smartAllowed() -> c.queue.smartShuffle.value = true
            else -> { c.queue.smartShuffle.value = false; it.shuffleModeEnabled = false }
        }
    }

    /**
     * A fresh random order every time shuffle is pressed (the player otherwise keeps reusing its old one),
     * with the current song first so everything else is still ahead of it.
     */
    private fun reshuffle(p: ExoPlayer) {
        val n = p.mediaItemCount
        if (n < 2) return
        val cur = p.currentMediaItemIndex.coerceIn(0, n - 1)
        p.setShuffleOrder(HistoryShuffleOrder.shuffled(n, cur) { p.currentMediaItemIndex })
    }
    fun cycleRepeat() = withPlayer {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private val _videoLoading = MutableStateFlow(false)
    /** True from tapping the video button until its first frame shows. */
    val videoLoading: StateFlow<Boolean> = _videoLoading

    fun setVideoMode(enabled: Boolean) {
        val s = service ?: return
        if (enabled && c.cast.casting) {
            _errors.tryEmit("Videos play on this phone. While casting, the speaker or TV gets the song")
            return
        }
        _videoLoading.value = enabled
        // An episode's picture is that episode's own choice; only music turns "keep playing videos" on.
        if (s.player.currentMediaItem?.toSong()?.isEpisode != true || !enabled) s.videoWanted = enabled
        scope.launch {
            s.setVideoMode(enabled)?.let { _errors.tryEmit(it); _videoLoading.value = false }
            if (!enabled) _videoLoading.value = false
            refresh()
        }
    }

    override fun onRenderedFirstFrame() {
        _videoLoading.value = false
    }

    suspend fun videoIdFor(song: Song): String? = service?.videoIdFor(song)?.id ?: if (song.isVideo) song.id else null

    private val _sleepEndOfSong = MutableStateFlow(false)
    /** The sleep timer is set to pause when the current song ends. */
    val sleepEndOfSong: StateFlow<Boolean> = _sleepEndOfSong

    fun setSleepEndOfSong(on: Boolean) {
        _sleepEndOfSong.value = on
        if (on) _sleepAt.value = 0
        withPlayer { it.pauseAtEndOfMediaItems = on }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        // Paused at the end of the song for the sleep timer: done; playing on from here goes on as usual.
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && _sleepEndOfSong.value) setSleepEndOfSong(false)
    }

    fun setSleepTimer(minutes: Int) {
        if (_sleepEndOfSong.value) setSleepEndOfSong(false)
        _sleepAt.value = if (minutes <= 0) 0 else System.currentTimeMillis() + minutes * 60_000L
        if (minutes > 0) scope.launch {
            val target = _sleepAt.value
            kotlinx.coroutines.delay(minutes * 60_000L)
            if (_sleepAt.value == target) {
                player?.pause()
                _sleepAt.value = 0
            }
        }
    }

    val position: Long get() = player?.currentPosition ?: 0
    val duration: Long get() = player?.duration?.takeIf { it > 0 } ?: ((_current.value?.durationSec ?: 0) * 1000L)
    val bufferedPosition: Long get() = player?.bufferedPosition ?: 0

    // ------------------------------------------------------------ State mirroring

    private fun refresh() {
        val p = player ?: return
        val item = p.currentMediaItem
        val song = item?.toSong()
        val changed = song?.id != _current.value?.id
        val wasLive = _current.value?.isLive == true
        _current.value = song
        _isPlaying.value = p.isPlaying
        _buffering.value = p.playbackState == Player.STATE_BUFFERING
        _index.value = p.currentMediaItemIndex
        _shuffle.value = p.shuffleModeEnabled
        _repeat.value = p.repeatMode
        _videoMode.value = item?.videoModeId != null
        _hasNext.value = p.hasNextMediaItem()
        _hasPrevious.value = p.hasPreviousMediaItem()
        // Keys stay stable when items move (id + occurrence), so a dragged row keeps its gesture.
        val seen = HashMap<String, Int>()
        _queue.value = (0 until p.mediaItemCount).mapNotNull { i ->
            val mi: MediaItem = p.getMediaItemAt(i)
            val n = seen.merge(mi.mediaId, 1, Int::plus) ?: 1
            mi.toSong()?.let { QueueEntry(i, it, "${mi.mediaId}#$n") }
        }
        _upNext.value = buildList {
            val tl = p.currentTimeline
            if (tl.isEmpty || p.currentMediaItemIndex !in 0 until tl.windowCount) return@buildList
            var i = tl.getNextWindowIndex(p.currentMediaItemIndex, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            while (i != C.INDEX_UNSET && size < tl.windowCount) { add(i); i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled) }
        }
        // Before the current song in play order, oldest first (#68: the queue only went forward).
        _played.value = buildList {
            val tl = p.currentTimeline
            if (tl.isEmpty || p.currentMediaItemIndex !in 0 until tl.windowCount) return@buildList
            var i = tl.getPreviousWindowIndex(p.currentMediaItemIndex, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            while (i != C.INDEX_UNSET && size < tl.windowCount) { add(i); i = tl.getPreviousWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled) }
        }.asReversed()
        if (song != null && (changed || song.isLive != wasLive)) checkVideo(song)
        updateLyricTiming()
    }

    /** Called when a music video failed to load; the button stays usable so it can be tried again. */
    internal fun onVideoFailed(songId: String) {
        _videoLoading.value = false
    }

    /**
     * Finds the song's music video as it starts (and warms it up), so the
     * button is ready. Greyed out only when there definitely isn't a video;
     * if YouTube couldn't be reached, it stays tappable to try again.
     */
    private fun checkVideo(song: Song) {
        _videoLoading.value = false
        // A live stream carries its own picture (the same HLS stream), so there's nothing to look up.
        if (song.isLive) { _videoAvailable.value = true; return }
        val saved = c.downloads.downloadedVideoId(song.id)
        if (saved == null && song.id in c.queue.noVideo) { _videoAvailable.value = false; return }
        val known = if (song.isVideo) song.id else saved ?: c.queue.counterparts[song.id]
        _videoAvailable.value = known != null
        val s = service ?: return
        if (known != null) { s.prefetchVideo(song, known); return }
        scope.launch {
            val found = s.videoIdFor(song)
            if (_current.value?.id != song.id) return@launch
            _videoAvailable.value = found.id != null || found.lookupFailed
            found.id?.let { s.prefetchVideo(song, it) }
        }
    }

    private val _jumps = MutableStateFlow(0)
    /** Moves on with every seek or jump of the playhead (the web remote sends its new place at once). */
    val jumps: StateFlow<Int> = _jumps

    override fun onEvents(player: Player, events: Player.Events) {
        refresh()
        if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) _jumps.value++
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        refresh()
    }
}
