package com.vinz.music

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.vinz.music.data.LibraryRepository
import com.prism.music.data.db.PrismDatabase
import com.vinz.music.data.innertube.InnerTube
import com.vinz.music.data.innertube.YouTubeMusic
import com.vinz.music.data.lyrics.LyricsRepository
import com.vinz.music.data.meta.MetaRepository
import com.vinz.music.data.prefs.SettingsRepository
import com.vinz.music.data.stream.CachedFormat
import com.vinz.music.data.stream.ResolvedStream
import com.vinz.music.data.stream.StreamResolver
import com.vinz.music.data.stream.YouTubeStreamInterceptor
import com.vinz.music.data.stream.ForgetOnForbidden
import com.vinz.music.download.DownloadRepository
import com.vinz.music.playback.NerdStats
import com.vinz.music.playback.PlayerConnection
import com.vinz.music.playback.QueueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** About 10 seconds of a song's stream (Opus ~160 kbps, AAC up to 256 kbps), saved ahead by [AppContainer.warmUp]. */
private const val PREBUFFER_BYTES = 320L * 1024
private const val YIELD_MS = 8_000L

class VinzApp : Application(), coil3.SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    /**
     * Every image in Vinz goes through here: covers saved on the phone are shown from their files (offline too, #54),
     * and data saver asks for smaller pictures on mobile data (#40). The rest is Coil's usual memory and disk caching.
     */
    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader =
        coil3.ImageLoader.Builder(context)
            .components {
                // First, so a cover kept on the phone is trimmed the same as one from the network (#92).
                add(com.vinz.music.data.VideoCoverInterceptor { ::container.isInitialized && container.settings.current.squareVideoCovers })
                add(com.vinz.music.data.OfflineArtInterceptor { if (::container.isInitialized) container.artwork else null })
                // #520: local albums' covers sharp on the player and album page (lists keep the small one).
                add(com.vinz.music.data.local.LocalFullArtInterceptor { if (::container.isInitialized) container.localMusic else null })
                add(com.vinz.music.data.DataSaverInterceptor { ::container.isInitialized && container.dataSaving() })
            }
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(File(context.cacheDir, "image_cache").absolutePath.toPath())
                    .maxSizePercent(0.02)
                    .build()
            }
            .build()

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))

    /** The language Vinz started in: a change of it from outside Vinz restarts Vinz ([onConfigurationChanged]). */
    private var language = ""

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Menus' labels and other text read once would stay in the old language (seen after a change in the phone's
        // Settings → Apps → Vinz → Language): Vinz starts again in the new one, as its own Language page does.
        if (AppLanguage.changed(this, language, newConfig)) AppLanguage.restart(this)
    }

    /**
     * Never called on a phone (Android just ends the process); Robolectric calls it after each test. The app's work stops
     * and its database closes then: the library's lists kept reading a database Robolectric had already let go of, and
     * the test JVM crashed in SQLite's native code (2026-11-07, two full runs in a row).
     */
    override fun onTerminate() {
        if (::container.isInitialized) {
            // Cancelled and waited for (briefly): a read already inside SQLite finishes before the database closes. Work
            // waiting for the main thread can't finish while this holds it, hence the short wait (2 s made the unit tests
            // four times as long).
            runCatching {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(300) { container.scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
                }
            }
            container.scope.cancel()
            runCatching { container.db.close() }
        }
        super.onTerminate()
    }

    override fun onCreate() {
        super.onCreate()
        // Before anything shows a word: Vinz's text in the listener's language (Strings.kt).
        Strings.resources = resources
        language = AppLanguage.shown(this)
        // A crash's details kept on the phone (Settings → About → Diagnostics), from the very start.
        CrashLog.install(this)
        // A restore from a backup goes in before anything opens the files it replaces.
        com.vinz.music.data.Backup.applyPending(this)
        // Before any icon is built: they're drawn in the style picked in Settings → Appearance → Icons.
        com.vinz.music.ui.theme.IconStyle.load(this)
        com.vinz.music.ui.theme.AppFonts.init(this)
        container = AppContainer(this)
        // How much of the processor heavy work may take: see BackgroundBudget.
        com.vinz.music.playback.BackgroundBudget.where = {
            when {
                container.visible.value -> com.vinz.music.playback.BackgroundBudget.Where.ON_SCREEN
                container.player.isPlaying.value || container.player.buffering.value -> com.vinz.music.playback.BackgroundBudget.Where.PLAYING
                else -> com.vinz.music.playback.BackgroundBudget.Where.AWAY
            }
        }
        com.vinz.music.playback.BackgroundBudget.slowPhone = { com.vinz.music.playback.MixTempo.slowPhone() }
        // Android's own backup restored onto another install: its sign-ins aren't this phone's (RestoredInstall).
        runCatching {
            com.vinz.music.data.RestoredInstall.check(this) {
                // Signed out as Settings does: the account's library and Home kept for offline, and the sign-in page's
                // cookies (Android's backup leaves those out now, but an older backup may have brought them).
                container.settings.logout()
                runCatching { container.library.forgetAccount() }
                runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null) }
                runCatching { container.lastFm.signOut() }
                runCatching { container.discord.signOut() }
            }
        }
        // Whether any of Vinz's screens is showing: what Android 12+ allows depends on it (DownloadRepository).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: android.app.Activity) { started++; container.visible.value = true }
            override fun onActivityStopped(activity: android.app.Activity) { started = (started - 1).coerceAtLeast(0); container.visible.value = started > 0 }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        runCatching { com.vinz.music.data.Notifications.createChannels(this) }
        // Long-press the app icon: Search, Recognize a song, Liked songs, Downloads (Reddit 1.7).
        // Off the main thread: it talks to the launcher's service and loads their icons, and start-up shouldn't wait.
        Thread({ runCatching { com.vinz.music.AppShortcuts.publish(this) } }, "VinzShortcuts").start()
        // For you (1.7): Home gets its sections once, and Release Radar looks round every few days.
        container.settings.addNewHomeSections()
        // The scheduled jobs are set up off the main thread: WorkManager opens its own database the first time, and on a
        // slow phone start-up shouldn't wait for it (GitHub #103: a budget phone's Vinz kept restarting).
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.vinz.music.data.foryou.ReleaseRadar.schedule(this@VinzApp) }
            // Rewind: the 1 December notification, and last year kept once it's over (after start-up has settled).
            runCatching { com.vinz.music.data.Rewinds.schedule(this@VinzApp) }
            // The monthly recap's notice (1.8.5), posted only if its switch is on when the 1st comes.
            runCatching { com.vinz.music.data.Recaps.schedule(this@VinzApp) }
            // DJ mode: the songs played most and liked get their key read once a day, on Wi-Fi while charging.
            runCatching { com.vinz.music.playback.DjPrescanWorker.schedule(this@VinzApp) }
            // Bring your history: the daily look at whether a fresh Spotify or Takeout download is due (only once one
            // was imported; a restored backup brings the dates with it).
            runCatching { com.vinz.music.data.imports.ImportReminders.schedule(this@VinzApp) }
        }
        // YouTube Music (live), 1.8.1: its job only while signed in and turned on; signing out stops it (read from its
        // preferences, so nothing is built for the many who never turn it on).
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var was: Boolean? = null
            container.settings.flow.map { it.isLoggedIn }.distinctUntilChanged().collect { signedIn ->
                runCatching {
                    com.vinz.music.data.imports.YouTubeLive.signInChanged(
                        this@VinzApp, signedIn, was, com.vinz.music.data.imports.YouTubeLiveWorker.Jobs(this@VinzApp),
                    )
                }
                was = signedIn
            }
        }
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(45_000)
            container.quietNetwork()
            runCatching { container.rewind.freezeLastYear() }
        }
        // Scrobbles made offline go to Last.fm once the phone is back online (#133).
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!container.lastFm.available) return@launch
            container.network.online.collect { online -> if (online && container.lastFm.user.value != null) runCatching { container.lastFm.flush() } }
        }
        // A restored backup's settings (since 1.7.8 kept as values beside the store) go in at once, before Home shows.
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { container.backup.applyRestoredSettings() } }
        // Downloads a restore left waiting carry on once the first screen is up (the download manager's calls go on the
        // main thread).
        container.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            kotlinx.coroutines.delay(1_500)
            runCatching { container.backup.resumeDownloads() }
        }
        // Diagnostics also watch the screen's thread (1.7.4, #100): work that holds it up long enough to drop frames is
        // logged with where it was. The test copy (".dev") also says so in logcat (VinzStall).
        com.vinz.music.playback.AudioDiagnostics.init(this)
        // How the last run ended, when it wasn't a Java crash (killed for memory, ANR, native crash: #103), off the
        // main thread (an ANR's trace can be large).
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { CrashLog.recordExits(this@VinzApp) } }
        com.vinz.music.playback.MainThreadWatch.toLogcat = BuildConfig.APPLICATION_ID.endsWith(".dev")
        container.scope.launch {
            container.settings.flow.map { it.audioDiagnostics }.distinctUntilChanged().collectLatest { on ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { com.vinz.music.playback.MainThreadWatch.setEnabled(on) }
                // And what Vinz's memory is made of, every half minute (#103/#134: the phone closed Vinz with gigabytes
                // in use, and the kind that grew says where to look).
                if (on) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    while (true) {
                        runCatching { com.vinz.music.playback.AudioDiagnostics.log("memory", MemoryLine.now()) }
                        kotlinx.coroutines.delay(30_000)
                    }
                }
            }
        }
        // A lyrics save into a music file that Vinz closing cut short is put right (#16).
        if (java.io.File(filesDir, "lyrics-tags").list()?.isNotEmpty() == true) {
            container.scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { container.lyricsToFiles.recover() } }
        }
        // Debug builds: song ids pushed to …/files/scan_list.txt are scanned (tempo, loudness, key) and logged under
        // VinzMix, for tuning the analysis on real songs (with mix_dump there too, their decoded audio is kept).
        if (BuildConfig.DEBUG) getExternalFilesDir(null)?.let { java.io.File(it, "scan_list.txt") }?.takeIf { it.isFile }?.let { list ->
            container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                for (id in list.readLines().map { it.trim() }.filter { it.isNotEmpty() }) {
                    runCatching { container.mixTempo.measure(id, network = true, edges = true, key = true) }
                }
                android.util.Log.i("VinzMix", "scan list done")
                list.delete()
            }
        }
        // Debug builds: renderers by description address, one per line in …/files/connect_renderer.txt, for casting to a
        // pretend one on a PC through adb (SSDP doesn't cross it); see SimulatedRendererTest.castFromThePhone. A
        // "googlecast://host:port" line adds a Google Cast device the same way (GoogleCastTest.castFromThePhone).
        if (BuildConfig.DEBUG) getExternalFilesDir(null)?.let { java.io.File(it, "connect_renderer.txt") }?.takeIf { it.isFile }?.let { f ->
            f.readLines().map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("googlecast://") }.forEach { container.cast.addByLocation(it) }
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as VinzApp).container

/** One line of what this process's memory is made of, for diagnostics (in MB, as Android's own meminfo counts it). */
internal object MemoryLine {
    fun now(): String {
        val mi = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
        fun mb(stat: String) = (mi.getMemoryStat(stat)?.toLongOrNull() ?: 0L) / 1024
        val threads = java.io.File("/proc/self/task").list()?.size ?: Thread.activeCount()
        val rt = Runtime.getRuntime()
        return "total ${mb("summary.total-pss")} MB (Java ${mb("summary.java-heap")}, native ${mb("summary.native-heap")}, " +
            "graphics ${mb("summary.graphics")}, code ${mb("summary.code")}, other ${mb("summary.private-other")}, swapped ${mb("summary.total-swap")}); " +
            "Java heap ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} of ${rt.maxMemory() / 1_048_576} MB; $threads threads; " +
            "processor since the last line ${cpuShare()}"
    }

    private var cpuAt = 0L
    private var wallAt = 0L

    /** Vinz's whole process, as a share of one core (#373: Android closes a background app for this figure). */
    @Synchronized private fun cpuShare(): String {
        val cpu = android.os.Process.getElapsedCpuTime()
        val wall = android.os.SystemClock.elapsedRealtime()
        val share = if (wallAt > 0 && wall > wallAt) "${(cpu - cpuAt) * 100 / (wall - wallAt)}% of a core" else "not known yet"
        cpuAt = cpu; wallAt = wall
        return share
    }
}

/** Hand-rolled dependency graph; everything lives for the app process. */
@OptIn(UnstableApi::class)
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One of Vinz's screens is showing (an activity is started); set by [VinzApp]. */
    val visible = MutableStateFlow(false)

    /**
     * Shows Android's notification prompt (set by the activity on screen). It isn't asked at start-up any more, with
     * nothing on screen to say why: the tour's "Stay in the loop" step asks, or else the first download does.
     */
    @Volatile var notificationPrompt: (() -> Unit)? = null

    /**
     * Whether Vinz had been set up before this run (welcome screen done, or signed in): the tour greets a new listener
     * and someone updating differently. Set once, when the activity first starts.
     */
    @Volatile var returningUser: Boolean? = null

    /** Settings → About → "Take the tour": the activity shows the whole tour. */
    val tourRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Asks for notifications once ever, when Vinz may not post them yet and is on screen to ask. */
    fun askNotifications() {
        if (android.os.Build.VERSION.SDK_INT < 33 || com.vinz.music.data.Notifications.allowed(app)) return
        val prefs = app.getSharedPreferences("prompts", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("notifications_asked", false)) return
        val prompt = notificationPrompt ?: return
        prefs.edit().putBoolean("notifications_asked", true).apply()
        prompt()
    }
    val settings = SettingsRepository(app, scope)
    /** Looks (1.7.3): saved, Vinz's own and shared looks, Undo for look changes, the day / night schedule. */
    val looks = com.vinz.music.data.looks.LookStore(app, settings, scope)
    /** Looks people shared on GitHub (themes/ in Vinz's repository), fetched only when the listener opens the list. */
    val lookGallery by lazy { com.vinz.music.data.looks.LookGallery(http, java.io.File(app.filesDir, "looks"), { settings.lookKinds }) }
    /** "Customize this" (1.7.3): with it on, the mini player, tab bar, player and Home's sections open their own settings. */
    val customizing = MutableStateFlow(false)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .apply { TestHooks.network?.let(::addInterceptor) }
        .build()
    /**
     * googlevideo's links. 1.8.7 (#408): each fetch notes its connection's address family (never the address), the
     * resolver hears how each link fared, and links asked for over IPv4 only are fetched over IPv4 only too.
     */
    internal val streamHttp: OkHttpClient = run {
        val chunks = http.newBuilder().addNetworkInterceptor(com.vinz.music.data.stream.NetFamily.Tag).build()
        val ipv4 = chunks.newBuilder().dns(com.vinz.music.data.stream.Ipv4OnlyDns()).build()
        val watch = com.vinz.music.data.stream.StreamWatch { url, code, family -> resolverMade?.fetched(url, code, family) }
        http.newBuilder()
            .addInterceptor(YouTubeStreamInterceptor(chunks, ipv4Client = ipv4, watch = watch))
            .addNetworkInterceptor(com.vinz.music.data.stream.NetFamily.Tag)
            .build()
    }

    /** The resolver once it's made: a fetch before then (a Canvas clip) has nothing to tell it, and doesn't make it. */
    @Volatile private var resolverMade: StreamResolver? = null

    val innerTube = InnerTube(http, { settings.current }, settings::setVisitorData)
    val ytm = YouTubeMusic(
        innerTube,
        songsEverywhere = { settings.current.let { it.radioSongsOnly && it.songsEverywhere } },
        onPairs = { pairs ->
            pairs.forEach { (song, video) -> queue.counterparts[song.id] = video }
            plays.learnAll(pairs.map { (song, video) -> video to song }, com.vinz.music.data.meta.PairSource.ALBUM_PAGE)
        },
    ).apply { moodsCache = com.vinz.music.data.MoodsCache(app.filesDir) }
    val db = PrismDatabase.build(app)
    /** Versions of songs the listener picked to hear instead (1.7.9, "Play a different version"). */
    val versions = com.vinz.music.data.SongVersions(app.getSharedPreferences("song_versions", android.content.Context.MODE_PRIVATE))
    /** 1.8.7 §3.2: what plays for each row, one answer for playback, downloads, covers, history and lyrics. */
    val plays = com.vinz.music.data.meta.PlaysAs(com.vinz.music.data.meta.PlaysAs.fileIn(app.filesDir), scope, pickOf = { versions.picks.value[it] })
    val streams by lazy { StreamResolver(app, http, settings, innerTube).apply { versionOf = versions::streamedOf; resolverMade = this } }
    val translator = com.vinz.music.data.lyrics.LyricsTranslator(http, File(app.filesDir, "translations"))
    /** Artist pages' Wikipedia bios (1.8.2), kept with the artist pages. Asks nothing unless that switch is on. */
    val artistBios by lazy {
        val wikipedia = com.vinz.music.data.WikipediaBios(http, "Vinz/${BuildConfig.VERSION_NAME} (https://github.com/nastechtest/Vinz)")
        com.vinz.music.data.ArtistBios(File(app.filesDir, "offline/bios"), wikipedia::find)
    }
    val network = com.vinz.music.data.NetworkState(app)
    /** Covers kept on the phone for downloads and the library, so they show offline. */
    val artwork = com.vinz.music.data.ArtworkStore(app, http) { quietNetwork() }
    /** The library's lists and opened playlists and albums, kept so they open offline. */
    val offline = com.vinz.music.data.OfflineLibrary(app)
    /** The music the listener uploaded to YouTube Music (Library → Uploads). */
    val uploads by lazy { com.vinz.music.data.Uploads(this) }
    /** The last Home, shown at once at the next start while the new one loads (#126). */
    val homeCache = com.vinz.music.data.HomeCache(app.filesDir)
    /** Albums and playlists played lately (Home's Recently played, #196). */
    val recentCollections by lazy { com.vinz.music.data.RecentCollections(app) }
    /** Last.fm scrobbling, when the listener connects their account (#133). */
    val lastFm by lazy { com.vinz.music.data.lastfm.LastFm(app, http, scope) }
    val lyrics = LyricsRepository(http, db.lyrics(), ytm) { settings.current }.apply {
        choices = app.getSharedPreferences("lyrics_choice", android.content.Context.MODE_PRIVATE)
        searchNames = app.getSharedPreferences("lyrics_search_as", android.content.Context.MODE_PRIVATE)
        wordShifts = app.getSharedPreferences("lyrics_word_shift", android.content.Context.MODE_PRIVATE)
        online = { network.isOnline() }
        // #344: the same files "Save lyrics into your music files" writes to, read only once they're switched on.
        val files = com.vinz.music.data.local.EmbeddedLyricsFiles(app) { s ->
            if (s.isLocal) localMusic.uriFor(s.id) else localLossless.match(s)?.uri
        }
        fileLyrics = { s -> if (settings.current.behavior.embeddedLyrics) files.lyricsOf(s) else null }
    }
    /** Search by a line of the lyrics, from the box itself (1.7.6). */
    val lyricSearch = com.vinz.music.data.lyrics.LyricSearch(http, ytm, db.lyricsSearch(), db.songs(), lyrics::textLines)
    val meta = MetaRepository(http, db.meta())
    val canvas = com.vinz.music.data.canvas.CanvasRepository(http).apply {
        hiddenPrefs = app.getSharedPreferences("canvas_hidden", android.content.Context.MODE_PRIVATE)
        savedPrefs = app.getSharedPreferences("canvas_saved", android.content.Context.MODE_PRIVATE)
        useIndex(File(app.filesDir, "canvas_index.json"))
        // 1.8.7.6, "Spotify's canvas first": asked only with its switch on and the Spotify connection the web-player
        // session (read from its file alone: nothing is built or unsealed for it), whatever Vinz's own two Canvas
        // switches say; Performance mode pauses it (the settings used read it as off).
        vinzCovers = { com.vinz.music.data.canvas.vinzCoversOn(settings.current) }
        spotify = com.vinz.music.data.canvas.SpotifyCanvasSource(
            CoroutineScope(scope.coroutineContext + Dispatchers.IO),
            enabled = {
                com.vinz.music.data.canvas.spotifyCanvasOn(settings.current, com.vinz.music.data.spotify.SpotifyStore.sessionIn(spotifyAuthPrefs))
            },
            finder = { spotifyCanvasFinder },
            quietUntil = { this@AppContainer.spotify.web.quietUntil },
            file = File(app.filesDir, "spotify_canvas.json"),
            log = { com.vinz.music.playback.AudioDiagnostics.log("spotify", it) },
        )
    }
    /** The Spotify connection's own file ([com.vinz.music.data.spotify.SpotifyStore.AUTH_PREFS]), read without building it. */
    private val spotifyAuthPrefs by lazy { app.getSharedPreferences(com.vinz.music.data.spotify.SpotifyStore.AUTH_PREFS, android.content.Context.MODE_PRIVATE) }
    /** Spotify's canvas through the session's reads; built on the first ask. */
    private val spotifyCanvasFinder by lazy { com.vinz.music.data.spotify.SpotifyCanvas { op, vars -> spotify.web.read(op, vars) } }
    init {
        // Whether the session sign-in is on, for the rows and search lines that show only then (read off the main thread).
        scope.launch(Dispatchers.IO) { com.vinz.music.data.spotify.SpotifySessionNow.watch(spotifyAuthPrefs) }
    }
    val videoSync by lazy { com.vinz.music.playback.VideoSync(app, streamHttp, streams, scope) }
    /** Songs' tempo and edges, for Mix, and their loudness, for normalization. */
    val mixTempo by lazy {
        // Phones with few cores (or little memory, or Performance mode on): scans make way while the listener scrolls (#343).
        val slow = com.vinz.music.playback.MixTempo.isSlowPhone(Runtime.getRuntime().availableProcessors(), com.vinz.music.data.DevicePower.mayBeSlow(app), false)
        com.vinz.music.playback.MixTempo.slowPhone = { slow || settings.current.performanceMode }
        com.vinz.music.playback.MixTempo(app, streamHttp, streams, ::audioOnPhone, { yieldToPlaying(null) }, { player.isPlaying.value }, waves, traits)
    }
    /** What's measured of songs (tempo, key, loudness, energy), in one table that smart playlists filter on (1.8.3). */
    val traits by lazy { com.vinz.music.data.TraitsStore(db.traits(), scope) }
    /** Songs' loudness outlines, for the waveform progress bar (made by the song scan). */
    val waves by lazy { com.vinz.music.data.SongWaves(app) }

    /**
     * Where a song's audio is on the phone, for scanning it without the network: a local file as it is; a download or
     * a song cached whole, copied out of its cache into [scratch]. Null when it isn't all here.
     */
    internal fun audioOnPhone(id: String, scratch: File): android.net.Uri? {
        if (id.startsWith(com.vinz.music.data.model.Song.LOCAL_PREFIX)) return localMusic.uriFor(id)
        for ((cache, key) in listOf(downloadCache to id, playerCache to songCacheKey(id))) {
            if (!cache.fullyCached(key, 0, C.LENGTH_UNSET.toLong())) continue
            val ok = runCatching {
                val source = CacheDataSource(cache, null)
                source.open(DataSpec.Builder().setUri("vinz://audio/$id").setKey(key).build())
                try {
                    scratch.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = source.read(buf, 0, buf.size)
                            if (n == C.RESULT_END_OF_INPUT) break
                            out.write(buf, 0, n)
                        }
                    }
                } finally { source.close() }
            }.isSuccess
            if (ok) return android.net.Uri.fromFile(scratch)
        }
        return null
    }
    /** Per-song lyric timing nudges, in ms (positive = lyrics earlier). Keyed by song id, plus ":video" in video mode. */
    /** YouTube's loudness figure per song (dB from its reference level), kept for offline normalization. */
    val loudness: android.content.SharedPreferences = app.getSharedPreferences("loudness", android.content.Context.MODE_PRIVATE)
    val lyricOffsets: android.content.SharedPreferences = app.getSharedPreferences("lyric_offsets", android.content.Context.MODE_PRIVATE)

    init {
        // Misses are cheap to re-check and may have been wrong (e.g. a provider was down).
        scope.launch(Dispatchers.IO) { runCatching { db.lyrics().clearMisses() } }
    }
    val queue = QueueState()
    val nerdStats = MutableStateFlow(NerdStats())
    /** #519: Android refuses the effects the equalizer asks for (the playback service sets it; the Equalizer screen says so). */
    val eqRefused = MutableStateFlow(false)
    /** video id -> (source label, resolved stream) for the stats overlay */
    val streamDetails = ConcurrentHashMap<String, Pair<String, ResolvedStream?>>()
    /** song id -> the file its saved copy (Downloaded / Cached) came as, while that copy plays (1.8.7, #373). */
    private val copyFormats = ConcurrentHashMap<String, CachedFormat>()

    /**
     * Stats for nerds' line on where [songId]'s quality comes from: the saved copy that plays and its format, or Premium's
     * best held back by the mobile data quality. Null when there's nothing to say.
     */
    fun streamNote(songId: String): String? {
        val (source, s) = streamDetails[songId] ?: return null
        return com.vinz.music.ui.player.streamQualityNote(source, s?.heldBack, copyFormats[songId])
    }
    @Volatile var onStreamResolved: ((String) -> Unit)? = null

    val databaseProvider by lazy { StandaloneDatabaseProvider(app) }
    /** Bytes Smart Downloads' picks take up (kept current by [DownloadRepository]); the song cache has the rest of its space. */
    @Volatile var smartPickBytes = 0L
    /** The song cache keeps within Smart Downloads' space, less what its picks use. */
    val cacheEvictor by lazy {
        com.vinz.music.download.SmartCacheEvictor(
            maxBytes = { (settings.current.offlineGb * 1_073_741_824.0).toLong() - smartPickBytes },
            favoured = { id -> runCatching { id in library.likedIds.value }.getOrDefault(false) },
            totalBytes = { (settings.current.offlineGb * 1_073_741_824.0).toLong() },
        )
    }
    val playerCache: SimpleCache by lazy {
        SimpleCache(File(app.cacheDir, "song_cache"), cacheEvictor, databaseProvider)
    }
    /** Shrinks the song cache to fit, after Smart Downloads' space or its picks changed. */
    fun trimSongCache() { scope.launch(Dispatchers.IO) { runCatching { cacheEvictor.trim(playerCache) } } }
    val downloadCache: SimpleCache by lazy {
        SimpleCache(File(app.filesDir, "downloads"), NoOpCacheEvictor(), databaseProvider)
    }

    val artistPrefs = com.vinz.music.data.ArtistPrefs(app)
    /** The color picker's recent and starred colors. */
    val savedColors = com.vinz.music.data.prefs.SavedColors(app)
    /** Where each podcast episode was left off. */
    val episodes = com.vinz.music.data.EpisodeProgress(app)
    /** Podcasts added by their RSS feeds: on the phone, no YouTube (Podcasts → Add a podcast by RSS). */
    val rssPodcasts by lazy {
        com.vinz.music.data.podcast.RssPodcasts(
            File(app.filesDir, com.vinz.music.data.podcast.RssPodcasts.DIR),
            com.vinz.music.data.podcast.RssGuard.feedClient(TestHooks.network),
        )
    }
    val library = LibraryRepository(this)
    /** Liked songs mirrored into a real YouTube Music playlist, when the listener turns that on. */
    val likedPlaylist = com.vinz.music.data.LikedPlaylist(this)
    val localLossless = com.vinz.music.data.local.LocalLossless(app)
    /** Library → Local music: the phone's own music files. */
    val localMusic = com.vinz.music.data.local.LocalMusic(app, scope) { settings.current.behavior.localSkipFolders }.also { music ->
        music.start()
        // #207, #249: folders ticked or unticked (or brought back by a restored backup).
        scope.launch { settings.flow.map { it.behavior.localSkipFolders }.distinctUntilChanged().drop(1).collect { music.rescan() } }
    }
    val losslessSync by lazy { com.vinz.music.download.LosslessSync(app, this) }
    // Started only after localLossless exists: a launch from an earlier init block can run on a
    // background thread before this property is assigned.
    init { losslessSync.start() }
    val taste = com.vinz.music.data.TasteRepository(this)
    /** Big data files downloaded on first use (the Japanese reading dictionary, Sing's model). */
    val models = com.vinz.music.data.ModelStore(app, http)
    /** Playlists and saved albums kept on the phone (no account needed). */
    val vinzLibrary = com.vinz.music.data.VinzLibrary(java.io.File(app.filesDir, "library"))
    /** The listener's own tags on songs (1.8.3). */
    val tags by lazy { com.vinz.music.data.TagStore(db, scope, java.io.File(app.filesDir, "library")) }
    /** Smart playlists' songs (1.8.3): asked for when one opens, never at start. */
    val smart by lazy {
        com.vinz.music.data.SmartPlaylists(db, { downloads.completedSongs() }, { localMusic.tracks.value.map { it.song } }, { meta.cachedGenres(it) })
    }
    /**
     * Whole playlists for Library → Songs' "From" (1.8.3, #382): Vinz's own from the phone, YouTube's page after page
     * (the copy kept on the phone without a network). Only a whole read is kept (#473): a page that doesn't come is asked
     * again, and a short or cancelled read is read again next time.
     */
    val playlistSongs by lazy {
        com.vinz.music.data.PlaylistSongs { id ->
            vinzLibrary.list(id)?.let { com.vinz.music.data.PlaylistRead(it.songs, complete = true) } ?: run {
                val page = try { ytm.playlist(id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    // Offline: the copy on the phone is shown, but read live again next time.
                    val kept = offline.page(id) ?: offline.page("VL$id") ?: throw e
                    return@PlaylistSongs com.vinz.music.data.PlaylistRead(kept.songs.distinctBy { it.id }, complete = false)
                }
                com.vinz.music.data.readPlaylist(page, ytm::playlistPage)
            }
        }
    }
    /** "Measure now" on a smart playlist: the songs its Sound rules can't see yet, scanned one by one (Wi-Fi for streams). */
    val smartMeasure by lazy {
        com.vinz.music.data.SmartMeasure(scope, { id, network -> mixTempo.measure(id, network = network, key = true) }) {
            app.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == false
        }
    }
    /** The listener's Vinz Account: a name and picture kept on this phone ([com.vinz.music.data.VinzAccount]). */
    val account by lazy { com.vinz.music.data.VinzAccount(this) }

    /**
     * What Vinz calls the listener: their Vinz Account's name, else their YouTube Music account's, else "".
     * [first]: just the first name ("Sam's Phone").
     */
    fun displayName(first: Boolean = false): String {
        val name = account.profile.value?.name?.takeIf { it.isNotBlank() } ?: settings.current.accountName
        return if (first) name.trim().substringBefore(' ') else name.trim()
    }
    /** Lyrics in Latin letters (Korean, Japanese, Chinese, Arabic, Hindi…). */
    val romanizer = com.vinz.music.data.lyrics.Romanizer(
        http, db.romanized(), models, scope, app.getSharedPreferences("romanize_songs", android.content.Context.MODE_PRIVATE),
    )
    /** Titles in other scripts in Latin letters, for searching the library (1.8.3, #363); spelled when a search wants them. */
    val titleSpellings by lazy {
        com.vinz.music.data.TitleSpellings(db.romanized(), scope, { romanizer.japaneseReady }) { romanizer.spellTitle(it) }
    }
    /** Songs' best parts, from YouTube's "Most replayed" curves (and the chorus in the lyrics). */
    val bestParts = com.vinz.music.data.BestParts(this)

    /** One-off upkeep that runs once per install, in the background, after Vinz has settled. */
    private val migrations: android.content.SharedPreferences = app.getSharedPreferences("migrations", android.content.Context.MODE_PRIVATE)
    init {
        // Genre detection picked the wrong artist when several share a name (and now finds second
        // genres), so saved genres are looked up again once. Retried on a later launch if offline.
        if (!migrations.getBoolean("genres_refreshed_1", false)) scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(15_000)
            val songs = runCatching {
                (db.songs().liked() + db.songs().recentFlow(300).first()).map { it.toSong() }.distinctBy { it.id }
            }.getOrNull() ?: return@launch
            if (meta.refreshAll(songs)) migrations.edit().putBoolean("genres_refreshed_1", true).apply()
        }
        // Songs' genres come from iTunes (each song's own, by its artist's songs there): the refiner asks for one artist
        // at a time in the background, and the artists of the listener's own music are queued once, most played first.
        meta.startRefining(scope)
        if (!migrations.getBoolean("genres_itunes_1", false)) scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(20_000)
            val now = System.currentTimeMillis()
            val played = runCatching { db.plays().topArtists(now - 365L * 86_400_000, now, 200).map { it.artistName } }.getOrDefault(emptyList())
            val mine = runCatching { (db.songs().liked() + db.songs().recentFlow(300).first()).map { it.toSong() } }.getOrDefault(emptyList())
                .filter { !it.isLocal && !it.isEpisode }.distinctBy { it.id }.groupBy { it.primaryArtist }
            (played + mine.keys).filter { it.isNotBlank() }.distinct().forEach { meta.refineLater(it, mine[it].orEmpty()) }
            migrations.edit().putBoolean("genres_itunes_1", true).apply()
        }
        // 1.8.3: what earlier versions measured (in the scans' prefs) goes into the song_traits table, for the songs it has
        // no row for. Again after a restore that brought one without the other; nothing to do otherwise (a count).
        scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(25_000)
            runCatching {
                val m = mixTempo
                val copied = traits.copyMissing(m.measuredIds(), m::traitsFromPrefs)
                if (copied > 0) android.util.Log.i("VinzTraits", "copied $copied songs' measurements into song_traits")
            }
        }
        // 1.8.3: a restored backup's tags, read in once (a file check otherwise).
        scope.launch(Dispatchers.IO) {
            if (java.io.File(app.filesDir, "library/" + com.vinz.music.data.TagStore.PENDING).isFile) tags.readPending()
        }
        // GitHub #182: bringing the library over had starred every library song's artist; those come off once, unless the
        // account follows them. Retried on a later launch when the account's follows couldn't be asked for.
        scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(12_000)
            if (artistPrefs.starsRepaired) return@launch
            val signedIn = settings.current.isLoggedIn
            val followed = if (signedIn) runCatching { ytm.librarySubscriptions().map { it.id }.toSet() }.getOrNull() else null
            artistPrefs.repairBroughtStars(followed, signedIn)
        }
    }
    val downloads by lazy { DownloadRepository(app, this, scope) }

    /**
     * What's downloaded, for screens: empty until [downloads] has started, then kept in step by it. Screens read these
     * rather than [downloads] itself, which reads the download index and cache from disk when first touched (1.7.8's
     * check: Home's first frame waited 3.4 s for it on a slow phone). [downloads] starts in the background at launch.
     */
    val downloadState = kotlinx.coroutines.flow.MutableStateFlow<Map<String, com.vinz.music.download.DownloadInfo>>(emptyMap())
    val videoState = kotlinx.coroutines.flow.MutableStateFlow<Map<String, com.vinz.music.download.VideoDownload>>(emptyMap())
    /**
     * Rows whose download is kept under their song's id, row id -> song id ([com.vinz.music.download.forRow], #315):
     * music videos downloaded as their song, and (1.8.7 §3.2) while videos play as their songs, every video whose song
     * [plays] knows ([com.vinz.music.data.meta.PlaysAsRules.keptAs]).
     */
    val downloadStandIns = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    init {
        scope.launch {
            kotlinx.coroutines.flow.combine(
                plays.learned, plays.downloaded, versions.picks,
                settings.flow.map { com.vinz.music.download.swapsVideos(it) }.distinctUntilChanged(),
            ) { _, _, _, everywhere -> plays.keptAs(everywhere) }.collect { downloadStandIns.value = it }
        }
    }
    /** Songs left out when a whole list downloads (#221). */
    val downloadSkips by lazy { com.vinz.music.download.DownloadSkips(app) }
    /** Playlists that stay downloaded as they grow (#298). */
    val keptPlaylists by lazy { com.vinz.music.download.KeptPlaylists(this) }
    init {
        // Nothing runs for it until a playlist is kept.
        scope.launch {
            settings.flow.map { it.behavior.keptPlaylists.isNotEmpty() }.distinctUntilChanged().collectLatest { any -> if (any) keptPlaylists.watch() }
        }
    }
    val covers by lazy { com.vinz.music.data.PlaylistCovers(app) }
    // Read at start, so the songs' own covers (#305) are there for the media session and Android Auto from the first song.
    init { covers }
    /** Saves lyrics into your own music files' tags (#16). */
    val lyricsToFiles by lazy { com.vinz.music.data.local.LyricsToFiles(app, this) }
    val updates by lazy { com.vinz.music.data.UpdateChecker(app, http, scope) }
    val backup by lazy { com.vinz.music.data.Backup(this) }
    val player = PlayerConnection(app, this)
    @Volatile private var songStartedAt = 0L
    init { scope.launch { player.currentSong.collect { songStartedAt = android.os.SystemClock.elapsedRealtime() } } }

    /**
     * Waits until the network is free for background saving (offline copies of playlists, covers, download retries):
     * not while the music is buffering, nor in a song's first 20 s, when its stream is loading. Since 1.6.8 that saving
     * ran at every launch and sync, and on slower connections the music stalled behind it.
     */
    suspend fun quietNetwork() {
        while (player.buffering.value ||
            (player.isPlaying.value && android.os.SystemClock.elapsedRealtime() - songStartedAt < 20_000)
        ) kotlinx.coroutines.delay(2_000)
    }

    /** Sing: karaoke with the song's own backing (vocals separated on the phone). */
    val sing by lazy { com.vinz.music.playback.sing.Sing(this) }
    val dj = com.vinz.music.playback.DjMode()
    val exclusions by lazy { com.vinz.music.data.foryou.Exclusions(app) }
    val forYou by lazy { com.vinz.music.data.foryou.ForYou(this) }
    /** What the listener made of Vinz's recommendations: served, skipped, heard through (1.7.9). */
    val recFeedback by lazy { com.vinz.music.data.foryou.RecFeedback(app) }
    val releaseRadar by lazy { com.vinz.music.data.foryou.ReleaseRadar(this) }
    val blends by lazy { com.vinz.music.data.blend.Blends(this) }
    /** Vinz's radio and Home's lists and mixes, from the listener's own music on the phone. */
    val yourMusic by lazy { com.vinz.music.data.foryou.YourMusic(this) }
    /** New to you, Deep cuts and On this day (1.8.2): only made while they're on Home, and what tuned radios go by. */
    val discover by lazy { com.vinz.music.data.foryou.Discover(this) }
    /** The songs heard this session, for "No repeats this session" (1.8.2, #159). */
    val sessionHeard by lazy {
        val prefs = app.getSharedPreferences("session_heard", android.content.Context.MODE_PRIVATE)
        val serializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), Long.serializer())
        com.vinz.music.playback.SessionHeard(
            load = { runCatching { kotlinx.serialization.json.Json.decodeFromString(serializer, prefs.getString("heard", null) ?: "{}") }.getOrDefault(emptyMap()) },
            // Kept through a restart only while the switch is on, and never while history is paused (#296): then it's in memory only.
            save = {
                val b = settings.current.behavior
                if (b.autoplayNoRepeats && !com.vinz.music.data.HistoryPause.paused(b)) prefs.edit().putString("heard", kotlinx.serialization.json.Json.encodeToString(serializer, it)).apply()
            },
        )
    }
    /** Vinz's holiday mixes, Thanksgiving and Christmas (1.8.0), made when first opened in their season, or ahead on Wi-Fi. */
    val seasonal by lazy { com.vinz.music.data.seasonal.SeasonalMixes(this) }
    /** Sends a Blend's other half back over Wi-Fi, so both phones have it ([com.vinz.music.data.blend.BlendExchange]). */
    val blendExchange by lazy { com.vinz.music.data.blend.BlendExchange({ code, mine -> blends.import(code, mine) }, { blends.myName }) }
    val recognizer by lazy { com.vinz.music.data.recognize.Recognizer(this) }

    /** Headphone correction (AutoEq profiles per headphones). */
    /** Crossfade's plan and progress, for the player's mark and blended covers (1.7.8, A6). */
    val mixState = com.vinz.music.playback.MixState()
    /** Your own equalizer presets, and the one picked for each output (1.7.8, A3). */
    val eqPresets by lazy { com.vinz.music.data.prefs.EqPresetStore(app) }
    val headphoneEq by lazy { com.vinz.music.data.HeadphoneEq(app, http, scope, player.preferredOutput) }

    /** "Listening to Vinz" on the listener's Discord profile, when they've turned it on (Settings → Discord). */
    val discord = com.vinz.music.playback.DiscordPresence(app, http, scope)
    init {
        scope.launch {
            settings.flow.collect { s -> discord.setEnabled(s.discordPresence && discord.signedIn) }
        }
    }

    /** Listen Together sessions on the local network. */
    val together by lazy { com.vinz.music.playback.together.Together(app, this) }

    /** Vinz Connect: speakers and TVs on the network (UPnP / DLNA). */
    private val castHolder = lazy { com.vinz.music.connect.Cast(app, this) }
    val cast by castHolder
    /** Casting right now; asking doesn't set up Vinz Connect's casting (the notification asks at every update). */
    val castingNow: Boolean get() = castHolder.isInitialized() && cast.casting
    private val webRemoteMade = lazy { com.vinz.music.connect.WebRemote(app, this) }
    /** Vinz Connect: Vinz played from a browser on the same Wi-Fi (Settings → Vinz Connect). */
    val webRemote by webRemoteMade
    /** Vinz on a TV: this phone's own copy handed to a TV on the Wi-Fi while its settings group is switched on. */
    val tvInstall by lazy { com.vinz.music.connect.TvInstall { com.vinz.music.connect.TvInstall.installedApk(app.applicationInfo) } }
    /** Rewind: each year in music (Replay → a year). */
    val rewind by lazy { com.vinz.music.data.Rewinds(this) }
    /** A year's Rewind to open (Home's Rewind card, the 1 December notification): Replay shows it and clears this. */
    val rewindRequest = MutableStateFlow<Int?>(null)
    /** A month's recap to open on Replay (1.8.5: Home's card, the notice's link). */
    val recapRequest = MutableStateFlow<java.time.YearMonth?>(null)
    val recaps by lazy { com.vinz.music.data.Recaps(this) }
    val milestones by lazy { com.vinz.music.data.MilestoneWatch(this) }
    /**
     * Bring your history (1.8.1): plays and libraries from Spotify, Google Takeout and Last.fm. Replay counts its
     * plays through [com.vinz.music.data.imports.HistoryImports.replay], the mixes through its `taste`.
     */
    val imports by lazy { com.vinz.music.data.imports.HistoryImports(this) }

    /**
     * The optional Spotify connection (1.8.1): Spotify's official Web API through the listener's own developer app.
     * Made when first asked (Settings, the mixes' taste); never connected, it only reads its own preferences.
     */
    val spotify by lazy { com.vinz.music.data.spotify.Spotify(this) }

    /** Vinz Connect: playback moving between Vinz devices on the same Wi-Fi. */
    val handoff by lazy { com.vinz.music.connect.Handoff(app, this) }
    init {
        scope.launch {
            settings.flow.map { it.webRemote }.distinctUntilChanged().collect { on ->
                if (on) webRemote.setEnabled(true) else if (webRemoteMade.isInitialized()) webRemote.setEnabled(false)
            }
        }
        scope.launch {
            // A TV is always reachable for the phones paired with it (1.8.0: the phone as its remote); it takes new
            // pairings only while its pairing screen is open.
            settings.flow.map { it.handoffAvailable }.distinctUntilChanged().collect { on -> handoff.setAvailable(on || handoff.isTv) }
        }
        // White light / Spectrum: the home-screen widgets are drawn again to match.
        scope.launch {
            // Your light too (1.7.7): the widgets take its colors on Android 12+.
            settings.flow.map { listOf(it.lightStyle, it.spectrumPreset, it.spectrumStops, it.spectrumBands) }.distinctUntilChanged().drop(1).collect {
                com.vinz.music.widget.Widgets.refresh(app)
                com.vinz.music.widget.RecognizeWidgets.refresh(app)
            }
        }
    }

    /** Data saver is on and the phone is on mobile data (#40): smaller pictures, no artist photos or animated covers. */
    fun dataSaving(): Boolean = settings.current.dataSaver && network.metered.value

    /** Whether a song plays without the network: a file on the phone, a finished download, or a song cached whole. */
    fun availableOffline(id: String): Boolean =
        id.startsWith(com.vinz.music.data.model.Song.LOCAL_PREFIX) || downloads.isDownloaded(id) ||
            runCatching { playerCache.fullyCached(songCacheKey(id), 0, C.LENGTH_UNSET.toLong()) }.getOrDefault(false)

    private fun Cache.fullyCached(key: String, position: Long, length: Long): Boolean {
        val total = ContentMetadata.getContentLength(getContentMetadata(key))
        if (total <= 0) return false
        val len = if (length == C.LENGTH_UNSET.toLong()) total - position else length
        return len <= 0 || isCached(key, position, len)
    }

    /**
     * Songs YouTube won't play where the listener is, and the music video whose sound plays in their place (#93: the
     * video was allowed when its song wasn't). Set by the player when a song fails as unavailable; for this run only.
     * One of [plays]' sources ([com.vinz.music.data.meta.PlaysAs.blocked]).
     */
    val soundFrom: ConcurrentHashMap<String, String> get() = plays.blocked

    /**
     * 1.8.7 (#408): song downloads YouTube refused every way, and the music video whose sound comes down in their place
     * ([com.vinz.music.download.DownloadRepository]), as [soundFrom] plays it. Kept with the downloads (one that stops
     * and goes on later must go on from the same recording); forgotten when the download goes.
     */
    private val downloadSounds = app.getSharedPreferences(DOWNLOAD_SOUNDS, android.content.Context.MODE_PRIVATE)

    fun downloadSoundOf(id: String): String? = downloadSounds.getString(id, null)

    fun setDownloadSound(id: String, video: String?) {
        if (video == null) { if (downloadSounds.contains(id)) downloadSounds.edit().remove(id).apply() }
        else downloadSounds.edit().putString(id, video).apply()
    }

    /**
     * The song cache's key for song [id]'s sound: a picked version ([versions]) is kept under that recording's own id, so
     * the two recordings' bytes never mix. They shared the song's key before, cleared at each pick while the player was
     * still saving the one it had been playing: what it saved after went in under the song, and back on its own
     * recording the song couldn't be read and stopped playing (1.7.9's check, "Woke Up Older").
     */
    fun songCacheKey(id: String): String = versions.streamedOf(id) ?: id

    /**
     * Plays [pick] in [song]'s place from now on (null: its own sound again). The version's measurements go, and a
     * download comes down again as the new one (each recording keeps its own cached part, [songCacheKey]). Playing now,
     * the song starts again where it was.
     */
    fun pickVersion(song: com.vinz.music.data.model.Song, pick: com.vinz.music.data.SongVersions.Pick?) {
        if (versions.of(song.id) == pick?.id?.takeIf { it != song.id }) return
        versions.set(song.id, pick)
        soundFrom.remove(song.id)
        com.vinz.music.playback.AudioDiagnostics.log("song", "${song.id} plays as ${pick?.id ?: "itself"} from now on (picked by the listener)")
        scope.launch(Dispatchers.Main) {
            // #328: the queue's copies of it show the version (or the song again) in the notification, the car and widgets.
            player.refreshCovers { it.id == song.id }
            player.reloadIfCurrent(song.id) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    mixTempo.forget(song.id)
                    waves.forget(song.id)
                    // A file on the phone (1.8.3) is there already: a download stays the song's own.
                    if (pick?.id?.startsWith(com.vinz.music.data.model.Song.LOCAL_PREFIX) != true) downloads.redownload(song.id, unfinished = true)
                }
            }
        }
    }

    private fun resolveAudio(spec: DataSpec, useCaches: Boolean): DataSpec {
        val uri = spec.uri
        if (uri.scheme != "vinz") return spec
        val song = uri.lastPathSegment ?: return spec
        // Played (and cached) as the video it plays from, so its bytes never mix with the song's own. Downloads keep
        // the song's own key, so they aren't swapped.
        // A download refused every way comes down as its music video's sound (1.8.7, [downloadSoundOf]), under the song's
        // own key still.
        val sound = if (useCaches) soundFrom[song] else downloadSoundOf(song)
        // A picked version is cached under its own id too ([songCacheKey]); its download is the song's (fetched again
        // as the version when it was picked).
        val id = if (useCaches) sound ?: songCacheKey(song) else song
        if (useCaches) {
            val saved = sound ?: song
            if (downloadCache.fullyCached(saved, spec.position, spec.length)) {
                savedCopyPlays(song, "Downloaded", readFormat(downloadFormats, saved))
                return spec.buildUpon().setKey(saved).build()
            }
            if (playerCache.fullyCached(id, spec.position, spec.length)) {
                savedCopyPlays(song, "Cached", readFormat(cacheFormats, id))
                return spec.buildUpon().setKey(STREAMED + id).build()
            }
            copyFormats.remove(song)
        }
        // The listener's own upload (#101): only their account may play it, so it's asked for that way at once instead of
        // after two refusals.
        if (uri.getQueryParameter("upload") == "1") streams.forAccount(sound ?: song)
        val s = try {
            // The song's id, not its version's: the resolver plays the version for it.
            streams.resolve(sound ?: song)
        } catch (e: Exception) {
            throw com.vinz.music.data.stream.SongStreamException(song, e.message ?: tr(R.string.couldn_t_resolve_stream), e)
        }
        // The song's start was saved ahead from another format (the network changed since, so another quality
        // was picked): bytes of two formats can't be stitched together, so that start goes.
        val format = CachedFormat.of(s)
        prebuffered.remove(id)?.let { was -> if (!was.sameFile(format)) runCatching { playerCache.removeResource(id) } }
        if (useCaches) cacheFormat(id, format) else downloadFormat(id, format, spec.position)
        streamDetails[song] = (if (useCaches) "Streaming" else "Downloading") to s
        if (useCaches) onStreamResolved?.invoke(song)
        return spec.buildUpon().setUri(s.audioUrl).setKey(if (useCaches) STREAMED + id else id).build()
    }

    /** [id] is downloaded whole, so the player reads it from Downloads ([resolveAudio]). */
    fun downloadedWhole(id: String): Boolean = runCatching { downloadCache.fullyCached(id, 0, C.LENGTH_UNSET.toLong()) }.getOrDefault(false)

    /** The format (itag) each download's bytes are in so far, kept so a later part never comes from another one. */
    private val downloadFormats = app.getSharedPreferences("download_formats", android.content.Context.MODE_PRIVATE)

    /**
     * A download that stopped part-way and starts again later can be given another format (the account's link was
     * refused and the next came from elsewhere, or the network changed the quality). Its two parts were stitched
     * into one file nothing could read: GitHub #183, "Skipping atom with length > 2147483647" from Downloads while the
     * song streamed fine. When the format changed, what was saved goes and the download starts over.
     */
    private fun downloadFormat(id: String, format: CachedFormat, position: Long) {
        val before = readFormat(downloadFormats, id)
        // 1.8.7: the same itag can be another file (another length, the volume-levelled variant): only the same file
        // carries on. A download recorded before 1.8.7 (its itag alone) that stopped part-way starts over once.
        if (before?.sameFile(format) != true && (position > 0 || downloadCache.getCachedBytes(id, 0, Long.MAX_VALUE) > 0)) {
            runCatching { downloadCache.removeResource(id) }
            downloadFormats.edit().putString(id, format.encode()).apply()
            com.vinz.music.playback.AudioDiagnostics.log("download", "$id came as ${format.encode()}, not ${before?.encode() ?: "unrecorded"} as before: starting it over")
            throw IOException(tr(R.string.the_song_s_format_changed_part))
        }
        if (before != format) downloadFormats.edit().putString(id, format.encode()).apply()
    }

    /** Which file [id]'s saved bytes came as ([CachedFormat]; an Int before 1.8.7, the itag alone). Null: not recorded. */
    private fun readFormat(prefs: android.content.SharedPreferences, id: String): CachedFormat? = runCatching {
        try {
            CachedFormat.decode(prefs.getString(id, null))
        } catch (_: ClassCastException) {
            CachedFormat.decode(prefs.getInt(id, 0))
        }
    }.getOrNull()

    /** A saved copy of [song] plays ([source]: "Downloaded" or "Cached"), as [format]: Stats for nerds says which. */
    private fun savedCopyPlays(song: String, source: String, format: CachedFormat?) {
        if (format != null) copyFormats[song] = format else copyFormats.remove(song)
        // This session's stream is kept for the stats only when it's the same file as the copy: otherwise the copy's
        // own format (worked out from its size) shows, not a stream that isn't playing (#373).
        val stream = streamDetails[song]?.second?.takeIf { format != null && CachedFormat.of(it).sameFile(format) }
        streamDetails[song] = source to stream
        onStreamResolved?.invoke(song)
    }

    /** The format (itag) each song's cached part came in (1.7.8), so a later part never comes from another one. */
    private val cacheFormats = app.getSharedPreferences("cache_formats", android.content.Context.MODE_PRIVATE)

    /**
     * The song cache's side of [downloadFormat] (Reddit, 1.7.7: "Element 134 must be in a TrackEntry" on one song every
     * time): what's cached of [id] from another format goes before today's stream adds to it. Before 1.7.8 nothing was
     * recorded; such a song is caught when it can't be read ([forgetCachedAudio] from the player).
     */
    private fun cacheFormat(id: String, format: CachedFormat) {
        val before = readFormat(cacheFormats, id)
        if (before == format) return
        // 1.8.7 (plan 4.2): another file of the same itag (another length, the volume-levelled variant) isn't joined
        // on either: that made a malformed WebM that stopped seconds in. What's cached of it goes, not mixed.
        if (!mayAddFormat(before, format, playerCache.getCachedBytes(id, 0, Long.MAX_VALUE) > 0)) {
            runCatching { playerCache.removeResource(id) }
            com.vinz.music.playback.AudioDiagnostics.log("stream", "$id came as ${format.encode()}, not ${before?.encode() ?: "unrecorded"} as cached: its cached part goes")
        }
        cacheFormats.edit().putString(id, format.encode()).apply()
    }

    /**
     * [format] may be saved under [key]: nothing of it is cached yet, or what is came as that same file (recorded for
     * it, as the player's stream does). Blocking (the cache's own lock).
     */
    private fun sameFormat(key: String, format: CachedFormat): Boolean {
        val before = readFormat(cacheFormats, key)
        val cached = playerCache.getCachedBytes(key, 0, Long.MAX_VALUE) > 0
        if (!mayAddFormat(before, format, cached)) {
            com.vinz.music.playback.AudioDiagnostics.log("stream", "$key: not saved ahead as ${format.encode()}, its cached part is ${before?.encode() ?: "unrecorded"}")
            return false
        }
        if (before != format) cacheFormats.edit().putString(key, format.encode()).apply()
        return true
    }

    /** [id]'s cached part can't be read: it goes, with its format. Blocking (the cache's own lock). */
    fun forgetCachedAudio(id: String) {
        val key = songCacheKey(id)
        runCatching { playerCache.removeResource(key) }
        cacheFormats.edit().remove(key).apply()
    }

    /** A download was removed (or is being fetched again): its format no longer binds the next one. */
    fun forgetDownloadFormat(id: String) = downloadFormats.edit().remove(id).apply()

    /** The format (itag) a download came down in, when it's known (recorded since 1.7.7.1). */
    fun downloadedItag(id: String): Int? = readFormat(downloadFormats, id)?.itag

    companion object {
        /** The mark on a streamed song's key: Downloads holds no such key ([playbackDataSourceFactory]). */
        private const val STREAMED = "streamed:"

        /** The saved preferences [downloadSoundOf] reads (with the downloads in a backup). */
        const val DOWNLOAD_SOUNDS = "download_sound_from"

        /**
         * Records [id]'s full length in [cache] once all of it is saved there. The cache only learns a length from a
         * request without one (a song played through); Load ahead's request names its length, so a song it saved whole
         * didn't count as whole, and offline Vinz asked the network for it and skipped it (#213).
         */
        /**
         * Bytes of [now] may join what's cached of a song: nothing is ([cached] false), or it came as the same file
         * ([before]: the same itag and length, [CachedFormat.sameFile]; null when it wasn't recorded, which isn't taken on trust).
         */
        internal fun mayAddFormat(before: CachedFormat?, now: CachedFormat, cached: Boolean): Boolean = CachedFormat.mayAdd(before, now, cached)

        /**
         * The key a music video's picture is read by ([videoDataSourceFactory]); [whole]: all of it is saved in Downloads.
         * One that isn't streams under a key Downloads never holds, as a song does: read under the download's own key, a
         * part saved at another quality was read as far as it reached and the stream went on from there, two files
         * joined into one nothing could read (#411: "Skipping atom with length > 2147483647" at ~1:08, every retry).
         */
        internal fun pictureKey(id: String, whole: Boolean): String = if (whole) "video:$id" else "${STREAMED}video:$id"

        /**
         * [upstream], telling [invalidate] the song (from the key it's read by) whose link YouTube turned away, and whether
         * that was a refusal (403), so its next lookup gets a fresh link: the prebuffer's (1.8.7, #408), as a download's
         * and a picture's.
         */
        internal fun forgetsRefused(upstream: DataSource, invalidate: (String, Boolean) -> Unit): DataSource =
            ForgetOnForbidden(upstream) { key, code -> invalidate(key.removePrefix(STREAMED).removePrefix("video:"), code == 403) }

        fun markWhole(cache: Cache, id: String, length: Long) {
            if (length <= 0 || !cache.isCached(id, 0, length)) return
            runCatching { cache.applyContentMetadataMutations(id, ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, length) }) }
        }
    }

    /** Songs whose first seconds [warmUp] saved into the song cache, and the file they came from. */
    private val prebuffered = ConcurrentHashMap<String, CachedFormat>()

    /**
     * Gets a song ready to start the moment it's picked: its stream is resolved (the slow part, 1–2 s through
     * NewPipe) and, on Wi-Fi, its first ~10 seconds are saved into the song cache, so playback opens on bytes
     * already here instead of waiting on googlevideo. Blocking; call it off the main thread.
     */
    fun warmUp(
        id: String, whole: Boolean = false, cellular: Boolean = false,
        /** For Sing: waits while the player loads even the song itself, so the two never fetch it side by side. */
        forSing: Boolean = false,
        /** Handed the download as it starts (Sing stops it when it lets go of the song). */
        writer: ((androidx.media3.datasource.cache.CacheWriter) -> Unit)? = null,
    ) {
        if (id.startsWith(com.vinz.music.data.model.Song.LOCAL_PREFIX)) return // a file on the phone is ready already
        if (id.startsWith(com.vinz.music.data.model.Song.RSS_PREFIX)) return // an hour of a feed's audio isn't fetched on the chance
        if (downloadCache.fullyCached(id, 0, C.LENGTH_UNSET.toLong())) return
        yieldToPlaying(if (forSing) null else id)
        val s = runCatching { streams.resolve(id) }.getOrNull() ?: return // live streams and failures: nothing to save
        val metered = app.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
        // On mobile data only when allowed (Settings → Load ahead on mobile data).
        if (metered && !cellular) return
        // [whole] (Settings → Load ahead): the whole song, so it plays without buffering; it needs the stream's size.
        if (whole && s.contentLength <= 0) return
        // Saved under the key the player reads it by (a picked version's own, [songCacheKey]).
        val key = songCacheKey(id)
        if (!whole && prebuffered.containsKey(key)) return
        val bytes = if (whole) s.contentLength else PREBUFFER_BYTES.let { if (s.contentLength > 0) minOf(it, s.contentLength) else it }
        if (playerCache.isCached(key, 0, bytes)) return
        // Never added to a part cached in another format (1.8.1): the player's own stream keeps to the format it began
        // in ([cacheFormat]); saving the rest from another one joined two files into one nothing could read, kept till the
        // cache was cleared (Sing on a TV, Reddit: "I have to uninstall and reinstall the app to restore playback").
        val format = CachedFormat.of(s)
        if (!sameFormat(key, format)) return
        val spec = DataSpec.Builder().setUri(s.audioUrl).setKey(key).setPosition(0).setLength(bytes).build()
        // A link YouTube turns away here is forgotten (1.8.7, #408), as a download's is: the player would otherwise open
        // the song on the same refused link the prebuffer was given.
        val upstream = forgetsRefused(OkHttpDataSource.Factory(streamHttp).createDataSource()) { song, refused -> streams.invalidate(song, refused) }
        val source = CacheDataSource(playerCache, upstream)
        runCatching { androidx.media3.datasource.cache.CacheWriter(source, spec, null, null).also { writer?.invoke(it) }.cache() }
            .onSuccess {
                prebuffered[key] = format
                // Saved whole: say so, so it plays from here offline too (#213).
                if (whole) markWhole(playerCache, key, s.contentLength)
            }
            .onFailure { android.util.Log.w("VinzStream", "Couldn't save the start of $id ahead: $it") }
    }

    /**
     * Getting another song ready waits (up to [YIELD_MS]) while the song that's playing is loading, at its start or in a
     * stall. Resolving is heavy (NewPipe makes several requests and runs YouTube's player code), and several at once
     * slowed each other down: on the phone, resolves that took ~2 s on their own took 3–6 s side by side, the playing
     * song's own among them. [id]: the song being got ready (no wait when it's the one playing); null always waits.
     * Blocking; call it off the main thread.
     */
    fun yieldToPlaying(id: String?) {
        // Called as a song changes: the player's "loading" reaches this side a moment after the change itself.
        Thread.sleep(250)
        val until = android.os.SystemClock.elapsedRealtime() + YIELD_MS
        while (player.buffering.value && (id == null || player.currentSong.value?.id != id) && android.os.SystemClock.elapsedRealtime() < until) {
            Thread.sleep(150)
        }
    }

    /**
     * Download cache → song cache → network, with vinz:// URIs resolved lazily. Only a whole download is read from
     * Downloads: a song that streams (or plays from the song cache) has its key marked [STREAMED], which Downloads never
     * holds, and the song cache reads it without the mark. Before, a download still coming down was read as far as it
     * had got, and the stream went on from there: another version's (1.7.9's check, "Woke Up Older"), or another
     * format's, and the two couldn't be read as one.
     */
    fun playbackDataSourceFactory(): DataSource.Factory {
        val upstream = OkHttpDataSource.Factory(streamHttp)
        val songCache = CacheDataSource.Factory()
            .setCache(playerCache)
            .setCacheKeyFactory { it.key?.removePrefix(STREAMED) ?: it.uri.toString() }
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val downloadsFirst = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(songCache)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val songs = ResolvingDataSource.Factory(downloadsFirst) { spec -> resolveAudio(spec, true) }
        val rss = rssDataSourceFactory()
        return DataSource.Factory { com.vinz.music.data.podcast.RssOrSongSource(rss.createDataSource(), songs.createDataSource()) }
    }

    /**
     * An episode from a feed: the song cache (it's seeked around in, as any episode is), then its own audio link through
     * [com.vinz.music.data.podcast.RssGuard]'s client, which holds nothing of YouTube's.
     */
    private fun rssDataSourceFactory(): DataSource.Factory {
        val feedHttp = OkHttpDataSource.Factory(com.vinz.music.data.podcast.RssGuard.audioClient(TestHooks.network))
            .setUserAgent(com.vinz.music.data.podcast.RssGuard.userAgent)
        val cached = CacheDataSource.Factory()
            .setCache(playerCache)
            .setUpstreamDataSourceFactory(feedHttp)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return ResolvingDataSource.Factory(cached) { spec ->
            val id = spec.uri.lastPathSegment.orEmpty()
            val link = rssPodcasts.audioUrl(id) ?: throw com.vinz.music.data.podcast.RssException(com.vinz.music.data.podcast.RssProblem.EPISODE_GONE)
            streamDetails[id] = "Podcast feed" to null
            onStreamResolved?.invoke(id)
            spec.buildUpon().setUri(link).setKey(id).build()
        }
    }

    /** A music video's picture: from Downloads when all of it is saved there (key `video:<id>`), else streamed (never cached, [pictureKey]). */
    fun videoDataSourceFactory(): DataSource.Factory {
        val http = OkHttpDataSource.Factory(streamHttp)
        val downloadsFirst = CacheDataSource.Factory()
            .setCache(downloadCache)
            // A link YouTube turns away is forgotten (and its next lookup goes through NewPipe), as for songs.
            .setUpstreamDataSourceFactory { ForgetOnForbidden(http.createDataSource()) { key, code -> streams.invalidate(key.removePrefix(STREAMED).removePrefix("video:"), refused = code == 403) } }
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return ResolvingDataSource.Factory(downloadsFirst) { spec ->
            val id = spec.uri.lastPathSegment
            if (spec.uri.scheme != "vinz" || id == null) spec
            else if (downloadCache.fullyCached("video:$id", spec.position, spec.length)) spec.buildUpon().setKey(pictureKey(id, whole = true)).build()
            else spec.buildUpon().setUri(resolveVideoUrl(id)).setKey(pictureKey(id, whole = false)).build()
        }
    }

    private fun resolveVideoUrl(id: String): android.net.Uri {
        val s = try { streams.resolve(id, withVideo = true) } catch (e: Exception) { throw com.vinz.music.data.stream.SongStreamException(id, e.message ?: tr(R.string.couldn_t_resolve_stream), e) }
        return android.net.Uri.parse(s.videoUrl ?: throw com.vinz.music.data.stream.SongStreamException(id, "No video stream available"))
    }

    /**
     * Live streams: vinz://live/<id> becomes the stream's HLS manifest; its playlists and segments are plain GETs
     * (not the POST-and-range dance [YouTubeStreamInterceptor] does for files), and nothing is cached.
     */
    fun liveDataSourceFactory(): DataSource.Factory =
        ResolvingDataSource.Factory(OkHttpDataSource.Factory(http).setUserAgent(com.vinz.music.data.stream.DESKTOP_UA)) { spec ->
            val id = spec.uri.lastPathSegment
            if (spec.uri.scheme != "vinz" || id == null) spec else {
                val url = try { streams.liveManifest(id) } catch (e: Exception) { throw IOException(e.message ?: tr(R.string.couldn_t_load_the_live_stream), e) }
                streamDetails[id] = "Live" to streamDetails[id]?.second
                onStreamResolved?.invoke(id)
                spec.buildUpon().setUri(url).build()
            }
        }

    /** Song downloads (vinz://audio/<id>) and saved music videos' pictures (vinz://video/<id>). */
    fun downloadDataSourceFactory(): DataSource.Factory {
        val http = OkHttpDataSource.Factory(streamHttp)
        // A link YouTube turns away (it ran out mid-download) is forgotten, so the download's next try gets a fresh one
        // (#55): it used to try the same dead link until the download failed.
        // (A download coming down as its music video's sound forgets the video's link: that's the one it was given.)
        val upstream = DataSource.Factory {
            ForgetOnForbidden(http.createDataSource()) { key, code ->
                val id = key.removePrefix("video:")
                streams.invalidate(if (key.startsWith("video:")) id else downloadSoundOf(id) ?: id, refused = code == 403)
            }
        }
        return ResolvingDataSource.Factory(upstream) { spec ->
            val id = spec.uri.lastPathSegment
            if (spec.uri.scheme == "vinz" && spec.uri.authority == "video" && id != null) {
                spec.buildUpon().setUri(resolveVideoUrl(id)).setKey("video:$id").build()
            } else resolveAudio(spec, false)
        }
    }

    /**
     * A radio or mix's queue from YouTube Music, as songs when Settings → Playback → "Songs, not videos" is on (#39), with
     * each song's music video remembered for the Video button. [keepFirst]: a station's first item stays as it is
     * (it can be a live stream).
     */
    suspend fun radio(
        videoId: String?, playlistId: String?, params: String? = null, continuation: String? = null, keepFirst: Boolean = false,
    ): com.vinz.music.data.model.NextResult {
        val r = ytm.next(videoId, playlistId, params, continuation)
        plays.learnAll(r.songPairs(), com.vinz.music.data.meta.PairSource.RADIO)
        return (if (settings.current.radioSongsOnly) r.songsOnly(keepFirst) else r).also { queue.counterparts.putAll(it.counterparts) }
    }

    /** Animated-cover clips on the phone: recently played, and saved (from the player, by album, or with downloads). */
    val canvasStore by lazy { com.vinz.music.data.canvas.CanvasStore(app, http, canvas, ytm, databaseProvider, scope) }
    /** 1.8.6: Canvas loops cut from music videos, kept as small files (recent ones in the cache, downloads' for good). */
    val clipFiles by lazy {
        com.vinz.music.data.canvas.ClipFiles(File(app.cacheDir, "canvas_clips"), File(app.filesDir, "canvas_clips")) {
            // One 400 MB for everything recent that Canvas keeps (the settings say so): loops get what the animated covers leave,
            // never under 100 MB.
            (400L * 1024 * 1024 - canvasStore.recentBytes).coerceAtLeast(100L * 1024 * 1024)
        }
    }
    val canvasClips by lazy {
        // 1.8.7: the player answer the finder had for a video is the one the fetch right after it uses.
        val answers = com.vinz.music.data.canvas.PlayerAnswers()
        com.vinz.music.data.canvas.ClipRepository(
            db.canvasClips(),
            com.vinz.music.data.canvas.ClipFinder.create(ytm, innerTube, http, answers = answers),
            com.vinz.music.data.canvas.ClipFetcher(streamHttp, { id -> kotlinx.coroutines.withContext(Dispatchers.IO) { innerTube.visionOsPlayer(id) } }, clipFiles, answers = answers),
        ).apply { files = clipFiles; refiner = com.vinz.music.data.canvas.ClipDecode }
    }
    /** 1.8.6: the next two songs' loops got ready on Wi-Fi, and downloads' kept with them (plan-canvas 4.8). */
    val canvasAhead by lazy {
        com.vinz.music.data.canvas.CanvasAhead(scope, object : com.vinz.music.data.canvas.CanvasAhead.Facts {
            override fun settings() = settings.current
            override fun metered() = app.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
            // The song's loop switched off (its animated cover's own switch is read in hasCover).
            override fun hidden(songId: String) = songId in canvas.hiddenLoops.value
            override fun saved(song: com.vinz.music.data.model.Song) = canvas.isSaved(song)
            override fun savedById(songId: String) = canvas.saved.value.has(songId, null)
            override suspend fun choice(songId: String) = canvasClips.choice(songId)
            // Only an animated cover that may play counts ([canvasCoverWanted]): one that may not leaves room for the loop.
            // 1.8.7.6: Spotify's canvas is not counted (nor asked) here: it is never kept with a download (plan D5), so a
            // downloaded song keeps its loop from the video for when Spotify can't be asked.
            override suspend fun hasCover(song: com.vinz.music.data.model.Song) =
                com.vinz.music.data.canvas.canvasCoverWanted(settings.current, song.id in canvas.hidden.value) && canvas.canvasFor(song, spotifyOk = false) != null
            override suspend fun prepare(song: com.vinz.music.data.model.Song, height: Int, saved: Boolean, allowed: () -> Boolean) =
                canvasClips.prepare(song, height, saved, allowed)
            override suspend fun videoOf(songId: String) = canvasClips.row(songId)?.videoId
            override fun unsave(videoId: String) = clipFiles.unsave(videoId)
        })
    }
    fun songCacheBytes(): Long = runCatching { playerCache.cacheSpace }.getOrDefault(0)

    fun clearSongCache() {
        runCatching { playerCache.keys.toList().forEach { playerCache.removeResource(it) } }
    }
}
