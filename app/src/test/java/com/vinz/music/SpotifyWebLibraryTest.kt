package com.vinz.music

import android.content.Context
import androidx.room.Room
import com.prism.music.data.db.PrismDatabase
import com.vinz.music.data.imports.HistoryImporter
import com.vinz.music.data.imports.MatchResult
import com.vinz.music.data.imports.MatchStatus
import com.vinz.music.data.imports.TrackToMatch
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import com.vinz.music.data.spotify.SessionFetch
import com.vinz.music.data.spotify.SessionFetcher
import com.vinz.music.data.spotify.SessionToken
import com.vinz.music.data.spotify.SessionTokens
import com.vinz.music.data.spotify.SpotifyApi
import com.vinz.music.data.spotify.SpotifyCodeBook
import com.vinz.music.data.spotify.SpotifyLikes
import com.vinz.music.data.spotify.SpotifyPlaylists
import com.vinz.music.data.spotify.SpotifyStore
import com.vinz.music.data.spotify.SpotifyWeb
import com.vinz.music.data.spotify.SpotifyWebParse
import com.vinz.music.data.spotify.WebLibrary
import com.prism.music.data.db.toEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * "Sign in to Spotify" (1.8.7.5): the library read through the session. The web player's answers become the same
 * tracks and playlists the Web API's do; playlists are mirrored and likes brought in, and nothing is written to Spotify.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SpotifyWebLibraryTest {
    private val app = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(app, PrismDatabase::class.java).allowMainThreadQueries().build()
    private val importer = HistoryImporter(db)
    private val auth = app.getSharedPreferences(SpotifyStore.AUTH_PREFS, Context.MODE_PRIVATE)
    private val prefs = app.getSharedPreferences(SpotifyStore.PREFS, Context.MODE_PRIVATE)
    private val store = SpotifyStore(auth, prefs, SpotifyFixtures.ReverseBox).apply {
        saveSession(SessionToken("sess", Long.MAX_VALUE))
        userId = "samr"
    }
    private val net = SpotifyFixtures.FakeSpotify()
    private val api = SpotifyApi(net.client, store, pause = {})
    private val tokens = SessionTokens(store, SessionFetcher { SessionFetch.Failed("not in this test") })
    private val web = SpotifyWeb(net.client, tokens, SpotifyCodeBook(prefs, net.client), userAgent = { "UA" }, pause = {})
    private var broughtIds: () -> Set<String> = { emptySet() }
    private val library = WebLibrary(web) { it.ownerId == "samr" || it.id in broughtIds() }
    private val dir = File(app.filesDir, "spotify-web-test").apply { deleteRecursively() }

    @After fun close() { db.close(); auth.edit().clear().commit(); prefs.edit().clear().commit(); dir.deleteRecursively() }

    private fun data(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun sent(i: Int) = Buffer().also { net.requests[i].body!!.writeTo(it) }.readUtf8()
    private fun ytSong(id: String, title: String, artist: String, sec: Int) = Song(id, title, listOf(ArtistRef(artist, "UC$id")), durationSec = sec, thumbnail = "https://i/$id.jpg")
    private fun matchOf(vararg songs: Pair<String, Song>): suspend (TrackToMatch) -> MatchResult = { t ->
        songs.firstOrNull { it.first == t.title }?.second?.let { MatchResult(MatchStatus.SURE, it, listOf(it)) } ?: MatchResult(MatchStatus.NONE, null, emptyList())
    }

    /** A track's "data" as the web player gives it ([lengthKey]: "duration" in Liked Songs, "trackDuration" in a playlist). */
    private fun trackData(id: String, name: String, artist: String, album: String, ms: Long, lengthKey: String = "duration", explicit: Boolean = false, uri: Boolean = false) = """
        {"__typename":"Track",${if (uri) "\"uri\":\"spotify:track:$id\"," else ""}"name":"$name","$lengthKey":{"totalMilliseconds":$ms},
         "artists":{"items":[{"uri":"spotify:artist:a$id","profile":{"name":"$artist"}}]},
         "albumOfTrack":{"uri":"spotify:album:al$id","name":"$album","coverArt":{"sources":[
            {"url":"https://i.scdn.co/image/64-$id","width":64,"height":64},{"url":"https://i.scdn.co/image/300-$id","width":300,"height":300},{"url":"https://i.scdn.co/image/640-$id","width":640,"height":640}]}},
         "contentRating":{"label":"${if (explicit) "EXPLICIT" else "NONE"}"},"discNumber":1,"trackNumber":1,"mediaType":"AUDIO","playability":{"playable":true}}
    """.trimIndent()

    private fun likedAnswer(total: Int, vararg tracks: Pair<String, String>) =
        """{"data":{"me":{"library":{"tracks":{"totalCount":$total,"items":[${tracks.joinToString(",") { (id, d) -> """{"addedAt":{"isoString":"2025-01-02T10:00:00Z"},"track":{"_uri":"spotify:track:$id","data":$d}}""" }}]}}}}}"""

    private fun playlistItem(id: String, name: String, owner: String, count: Int) =
        """{"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:$id","data":{"__typename":"Playlist","name":"$name","description":"",
           "ownerV2":{"data":{"__typename":"User","uri":"spotify:user:$owner","name":"Owner $owner"}},
           "images":{"items":[{"sources":[{"url":"https://mosaic.scdn.co/640/$id","width":640,"height":640}]}]}}}}"""

    private val pseudoLiked = """{"item":{"__typename":"PseudoPlaylistResponseWrapper","data":{"__typename":"PseudoPlaylist","name":"Liked Songs","count":2,"uri":"spotify:collection:tracks","image":{}}}}"""
    private val folder = """{"item":{"__typename":"FolderResponseWrapper","_uri":"spotify:user:samr:folder:f1","data":{"name":"A folder"}}}"""

    private fun libraryAnswer(total: Int, vararg items: String) = """{"data":{"me":{"libraryV3":{"totalCount":$total,"items":[${items.joinToString(",")}]}}}}"""

    private fun playlistAnswer(total: Int, vararg items: String) =
        """{"data":{"playlistV2":{"name":"Road","description":"d","content":{"totalCount":$total,"items":[${items.joinToString(",")}]}}}}"""

    private fun inPlaylist(d: String) = """{"itemV2":{"__typename":"TrackResponseWrapper","data":$d}}"""

    // ---- the answers, read

    @Test fun aLikedSongBecomesTheSameTrackTheWebApiGives() {
        val page = SpotifyWebParse.liked(data(likedAnswer(1028, "t1" to trackData("t1", "Blinding Lights", "The Weeknd", "After Hours", 200_040, explicit = true))).getValue("data").jsonObject)
        assertEquals(1028, page.total)
        val t = page.items.single().track
        assertEquals("spotify:track:t1", t.uri)
        assertEquals("Blinding Lights", t.title)
        assertEquals(listOf("The Weeknd"), t.artists)
        assertEquals("After Hours", t.album)
        assertEquals(200_040L, t.durationMs)
        assertTrue(t.explicit)
        assertEquals("a list-sized picture", "https://i.scdn.co/image/300-t1", t.image)
        assertNull("the web player's answers carry no ISRC", t.isrc)
        assertEquals(java.time.Instant.parse("2025-01-02T10:00:00Z").toEpochMilli(), page.items.single().addedAt)
    }

    @Test fun likedSongsAndFoldersAreNotPlaylists() {
        val page = SpotifyWebParse.playlists(data(libraryAnswer(4, pseudoLiked, playlistItem("p1", "Road", "samr", 40), folder, playlistItem("p2", "Lofi", "lofigirl", 300))).getValue("data").jsonObject)
        assertEquals(listOf("p1", "p2"), page.items.map { it.id })
        assertEquals(4, page.total)
        val p = page.items.first()
        assertEquals("Road", p.name); assertEquals("samr", p.ownerId); assertEquals("Owner samr", p.ownerName)
        assertEquals("the list names no song count", 0, p.total)
        assertEquals("https://mosaic.scdn.co/640/p1", p.image)
    }

    @Test fun aPlaylistsEpisodesAndLocalFilesLeaveGapsNotSongs() {
        val episode = """{"itemV2":{"__typename":"EpisodeResponseWrapper","data":{"name":"An episode","uri":"spotify:episode:e1"}}}"""
        val local = inPlaylist("""{"name":"x","uri":"spotify:local:::x:120","trackDuration":{"totalMilliseconds":120000}}""")
        val page = SpotifyWebParse.playlistItems(data(playlistAnswer(3, inPlaylist(trackData("t2", "Creep", "Radiohead", "Pablo Honey", 238_640, "trackDuration", uri = true)), episode, local)).getValue("data").jsonObject)
        assertEquals(3, page.total)
        assertEquals(listOf("Creep", null, null), page.items.map { it?.title })
        assertEquals(238_640L, page.items.first()!!.durationMs)
    }

    // ---- read page by page

    @Test fun likedSongsAreReadFiftyAtATimeUntilAllAreIn() = runBlocking {
        var call = 0
        net.routes[SpotifyWeb.URL] = {
            val offset = call++ * 50
            200 to likedAnswer(120, *(offset until minOf(offset + 50, 120)).map { "t$it" to trackData("t$it", "Song $it", "Artist", "Album", 180_000) }.toTypedArray())
        }
        assertEquals(120, library.savedTracks(5_000).size)
        assertEquals(3, net.requests.size)
        assertTrue(sent(1), sent(1).contains("\"offset\":50"))
        assertTrue(net.requests.all { it.method == "POST" && it.url.toString() == SpotifyWeb.URL })
    }

    @Test fun aChangedSongCountIsWhatTellsAPlaylistChanged() = runBlocking {
        // The list names no count: each of the listener's own playlists is asked for it, a followed one isn't.
        net.routes[SpotifyWeb.URL] = {
            if (sent(net.requests.size - 1).contains("\"libraryV3\"")) 200 to libraryAnswer(3, pseudoLiked, playlistItem("p1", "Road", "samr", 40), playlistItem("p2", "Lofi", "lofigirl", 300))
            else 200 to playlistAnswer(40)
        }
        val lists = library.playlists(10)
        assertEquals(listOf(WebLibrary.snapshotOf(40), ""), lists.map { it.snapshot })
        assertEquals(listOf(40, 0), lists.map { it.total })
        assertEquals("the list, and one small read for the own playlist", 2, net.requests.size)
        assertTrue(sent(1), sent(1).contains("\"limit\":1") && sent(1).contains("spotify:playlist:p1"))
        net.routes[SpotifyWeb.URL] = { 200 to playlistAnswer(41) }
        assertEquals(WebLibrary.snapshotOf(41) to "Road", library.playlistSnapshot("p1"))
        assertFalse(library.canWrite)
    }

    // ---- the features, on a session

    private class FakeHands : SpotifyPlaylists.Hands {
        val lists = linkedMapOf<String, Pair<String, List<Song>>>()
        override fun create(name: String, songs: List<Song>, description: String): String = "vinz${lists.size + 1}".also { lists[it] = name to songs }
        override fun replace(id: String, songs: List<Song>) { lists[id] = lists.getValue(id).first to songs }
        override fun rename(id: String, name: String, description: String) { lists[id] = name to lists.getValue(id).second }
        override fun exists(id: String) = id in lists
        override fun songIds(id: String) = lists[id]?.second?.map { it.id }.orEmpty()
        override fun delete(id: String) { lists.remove(id) }
    }

    @Test fun yourPlaylistsAreMirroredAndFollowedOnesOnlyNamed() = runBlocking {
        val song = ytSong("fHI8X4OXluQ", "Blinding Lights", "The Weeknd", 200)
        net.routes[SpotifyWeb.URL] = {
            val body = sent(net.requests.size - 1)
            if (body.contains("\"libraryV3\"")) 200 to libraryAnswer(3, pseudoLiked, playlistItem("p1", "Road", "samr", 1), playlistItem("p2", "Lofi", "lofigirl", 300))
            else 200 to playlistAnswer(1, inPlaylist(trackData("t1", "Blinding Lights", "The Weeknd", "After Hours", 200_040, "trackDuration", uri = true)))
        }
        val hands = FakeHands()
        val playlists = SpotifyPlaylists(api, db, importer, dir, hands, matchOf("Blinding Lights" to song), pause = {}, library = { library })
        playlists.refreshAll("samr", now = 1)
        assertEquals(listOf("p1"), playlists.mirror().lists.map { it.playlist.id })
        assertEquals(listOf("spotify:track:t1"), playlists.mirror().lists.single().tracks.map { it.uri })
        assertEquals(listOf("p2"), playlists.followed.value.map { it.id })
        assertEquals("only the web player's address was asked", 0, net.requests.count { it.url.host == "api.spotify.com" })
        // The same list again: the count is the same, so the songs themselves are compared (one page here).
        val before = net.requests.size
        playlists.refreshAll("samr", now = 2)
        assertEquals("the list, the own playlist's count, and its songs to compare", 3, net.requests.size - before)
        assertEquals("nothing changed: the same Vinz playlist, untouched", 1, hands.lists.size)
    }

    @Test fun aPlaylistThatSwapsASongAndKeepsItsLengthIsStillBroughtUpToDate() = runBlocking {
        // Lofi Girl's kind: always as many songs, different ones. The count alone would never tell.
        val first = ytSong("fHI8X4OXluQ", "Blinding Lights", "The Weeknd", 200)
        val second = ytSong("XFkzRNyygfk", "Creep", "Radiohead", 238)
        var now = trackData("t1", "Blinding Lights", "The Weeknd", "After Hours", 200_040, "trackDuration", uri = true)
        net.routes[SpotifyWeb.URL] = {
            val body = sent(net.requests.size - 1)
            if (body.contains("\"libraryV3\"")) 200 to libraryAnswer(2, pseudoLiked, playlistItem("p1", "Road", "samr", 1))
            else 200 to playlistAnswer(1, inPlaylist(now))
        }
        val hands = FakeHands()
        val playlists = SpotifyPlaylists(api, db, importer, dir, hands, matchOf("Blinding Lights" to first, "Creep" to second), pause = {}, library = { library })
        playlists.refreshAll("samr", now = 1)
        val vinzId = playlists.mirror().lists.single().vinzId!!
        playlists.refreshOne(vinzId, readAgain = false)
        assertEquals(listOf("fHI8X4OXluQ"), hands.songIds(vinzId))

        now = trackData("t2", "Creep", "Radiohead", "Pablo Honey", 238_640, "trackDuration", uri = true)
        playlists.refreshAll("samr", now = 2)
        assertEquals("still one song, a different one", listOf("spotify:track:t2"), playlists.mirror().lists.single().tracks.map { it.uri })
        playlists.refreshOne(vinzId, readAgain = false)
        assertEquals(listOf("XFkzRNyygfk"), hands.songIds(vinzId))

        // Opened: compared again only after a while (a long playlist is many reads).
        now = trackData("t1", "Blinding Lights", "The Weeknd", "After Hours", 200_040, "trackDuration", uri = true)
        playlists.refreshOne(vinzId, readAgain = true, now = 3)
        assertEquals("too soon to compare again", listOf("XFkzRNyygfk"), hands.songIds(vinzId))
        playlists.refreshOne(vinzId, readAgain = true, now = 3 + SpotifyPlaylists.LOOK_AGAIN_MS)
        assertEquals(listOf("fHI8X4OXluQ"), hands.songIds(vinzId))
    }

    @Test fun aPlaylistJustBroughtInSaysHowFarItsSongsHaveBeenLookedUp() = runBlocking {
        val n = SpotifyPlaylists.AT_ONCE_ON_OPEN + 5
        net.routes[SpotifyWeb.URL] = {
            val body = sent(net.requests.size - 1)
            when {
                body.contains("\"libraryV3\"") -> 200 to libraryAnswer(3, pseudoLiked, playlistItem("p1", "Road", "samr", 1), playlistItem("p2", "Lofi", "lofigirl", 300))
                body.contains("spotify:playlist:p2") -> 200 to playlistAnswer(n, *(1..n).map { inPlaylist(trackData("l$it", "Song $it", "Artist $it", "Album", 200_040, "trackDuration", uri = true)) }.toTypedArray())
                else -> 200 to playlistAnswer(1, inPlaylist(trackData("t1", "Creep", "Radiohead", "Pablo Honey", 238_640, "trackDuration", uri = true)))
            }
        }
        val hands = FakeHands()
        val playlists = SpotifyPlaylists(api, db, importer, dir, hands, matchOf(), pause = {}, library = { library })
        broughtIds = { playlists.mirror().brought.toSet() }
        playlists.refreshAll("samr", now = 1)
        val vinzId = playlists.bringIn("p2", now = 2)!!
        assertTrue("Library hears of the new Spotify playlist by itself", vinzId in playlists.mirrored.value)
        assertEquals(SpotifyPlaylists.Fill(SpotifyPlaylists.AT_ONCE_ON_OPEN, n), playlists.filling.value[vinzId])
        // The rest looked up (here by opening it; in the app, the background matching): nothing left to say.
        playlists.refreshOne(vinzId, readAgain = false)
        assertNull(playlists.filling.value[vinzId])
        playlists.forget(keep = true)
        assertTrue(playlists.mirrored.value.isEmpty())
    }

    @Test fun matchedSongsArePlacedEverySoManyOfARunNotOnlyAtItsEnd() {
        val steps = com.vinz.music.data.imports.PlaceSteps(20)
        assertEquals("a run that starts at 0, then 1, 2 … 100", listOf(20, 40, 60, 80, 100), (0..100).filter { steps.due(it) })
        assertFalse("the next run starts over", steps.due(3))
        assertTrue(steps.due(23))
    }

    @Test fun aFollowedPlaylistIsBroughtInWhenAskedAndStaysInStep() = runBlocking {
        val song = ytSong("fHI8X4OXluQ", "Blinding Lights", "The Weeknd", 200)
        var lofiSongs = 1
        net.routes[SpotifyWeb.URL] = {
            val body = sent(net.requests.size - 1)
            when {
                body.contains("\"libraryV3\"") -> 200 to libraryAnswer(3, pseudoLiked, playlistItem("p1", "Road", "samr", 1), playlistItem("p2", "Lofi", "lofigirl", 300))
                body.contains("spotify:playlist:p2") -> 200 to playlistAnswer(lofiSongs, *(1..lofiSongs).map { inPlaylist(trackData("l$it", if (it == 1) "Blinding Lights" else "Song $it", "The Weeknd", "After Hours", 200_040, "trackDuration", uri = true)) }.toTypedArray())
                else -> 200 to playlistAnswer(1, inPlaylist(trackData("t1", "Creep", "Radiohead", "Pablo Honey", 238_640, "trackDuration", uri = true)))
            }
        }
        val hands = FakeHands()
        val playlists = SpotifyPlaylists(api, db, importer, dir, hands, matchOf("Blinding Lights" to song), pause = {}, library = { library })
        broughtIds = { playlists.mirror().brought.toSet() }
        playlists.refreshAll("samr", now = 1)
        assertEquals("nothing followed is read until asked", listOf("p2"), playlists.followed.value.map { it.id })
        assertEquals(0, net.requests.count { sent(net.requests.indexOf(it)).contains("spotify:playlist:p2") })

        val vinzId = playlists.bringIn("p2", now = 2)!!
        assertEquals("Lofi", hands.lists.getValue(vinzId).first)
        assertEquals(listOf("fHI8X4OXluQ"), hands.lists.getValue(vinzId).second.map { it.id })
        assertTrue("no longer only named", playlists.followed.value.isEmpty())
        assertTrue(playlists.isMirror(vinzId))
        assertEquals("asked twice, it is the same playlist", vinzId, playlists.bringIn("p2", now = 3))

        // The next daily read keeps it mirrored, and reads it again when its song count changed.
        lofiSongs = 2
        playlists.refreshAll("samr", now = 4)
        assertEquals(listOf("p1", "p2"), playlists.mirror().lists.map { it.playlist.id })
        assertEquals(2, playlists.mirror().lists.last().tracks.size)
        assertTrue(playlists.followed.value.isEmpty())
        assertEquals(1, hands.lists.count { it.value.first == "Lofi" })
    }

    @Test fun followedPlaylistsTooBringsThemInByThemselvesLessTheHiddenOnes() = runBlocking {
        val followed = (1..SpotifyPlaylists.AUTO_FOLLOWED + 5).map { playlistItem("f$it", "Followed $it", "someone", 10) }
        net.routes[SpotifyWeb.URL] = {
            val body = sent(net.requests.size - 1)
            if (body.contains("\"libraryV3\"")) 200 to libraryAnswer(followed.size + 2, pseudoLiked, playlistItem("p1", "Road", "samr", 1), *followed.toTypedArray())
            else 200 to playlistAnswer(1, inPlaylist(trackData("t1", "Creep", "Radiohead", "Pablo Honey", 238_640, "trackDuration", uri = true)))
        }
        val hands = FakeHands()
        val playlists = SpotifyPlaylists(api, db, importer, dir, hands, matchOf(), pause = {}, library = { library })
        broughtIds = { playlists.mirror().brought.toSet() }
        // Off (as it is until the listener turns it on): only their own.
        playlists.refreshAll("samr", now = 1)
        assertEquals(listOf("p1"), playlists.mirror().lists.map { it.playlist.id })
        // On: the first fifty in Spotify's order, the hidden one left out; the rest stay named, a tap away.
        playlists.refreshAll("samr", now = 2, bringAll = true, except = setOf("f2"))
        val mirrored = playlists.mirror().lists.map { it.playlist.id }
        assertEquals(1 + SpotifyPlaylists.AUTO_FOLLOWED, mirrored.size)
        assertTrue("f1" in mirrored && "f2" !in mirrored && "f51" in mirrored && "f52" !in mirrored)
        assertEquals(listOf("f2") + (52..55).map { "f$it" }, playlists.followed.value.map { it.id })
        // Off again: the ones already in stay in.
        playlists.refreshAll("samr", now = 3)
        assertEquals(1 + SpotifyPlaylists.AUTO_FOLLOWED, playlists.mirror().lists.size)
    }

    @Test fun aDeveloperAppCannotBringAFollowedPlaylistIn() = runBlocking {
        store.saveTokens(com.vinz.music.data.spotify.SpotifyTokens("acc", "ref", Long.MAX_VALUE))
        val playlists = SpotifyPlaylists(api, db, importer, dir, FakeHands(), matchOf(), pause = {})
        assertNull(playlists.bringIn("p2"))
        assertEquals("nothing was asked", 0, net.requests.size)
    }

    @Test fun everyLikedSongIsLikedOnceMatchedWithoutWaitingForTheNextRun() = runBlocking {
        val n = SpotifyLikes.AT_ONCE + 5
        net.routes[SpotifyWeb.URL] = { 200 to likedAnswer(n, *(1..n).map { "t$it" to trackData("t$it", "Song $it", "Artist $it", "Album", 180_000) }.toTypedArray()) }
        val likedHere = mutableListOf<Song>()
        val match: suspend (TrackToMatch) -> MatchResult = { t -> ytSong("yt" + t.title.substringAfter(' ').padStart(9, '0'), t.title, t.artist, 180).let { MatchResult(MatchStatus.SURE, it, listOf(it)) } }
        val likes = SpotifyLikes(api, db, importer, dir, { songs ->
            likedHere += songs
            songs.forEach { db.songs().upsert(it.toEntity(db.songs().get(it.id)).copy(liked = true, likedAt = 500)) }
        }, { emptySet() }, match, pause = {}, library = { library })
        val first = likes.sync(now = 1)
        assertEquals("the first run matches a few at once", SpotifyLikes.AT_ONCE, likedHere.size)
        assertEquals(5, first.waitingHere.size)

        // The background matching gets to the rest (as ImportMatchWorker does), then says so.
        for (key in first.waitingHere.values) {
            val row = db.imports().match(key)!!
            importer.applyMatch(row, match(TrackToMatch(row.title, row.artist, row.album, row.durationMs, 0, row.isrc)))
        }
        val asked = net.requests.size
        assertEquals(5, likes.placeMatched())
        assertEquals("all of them are liked here now", n, likedHere.map { it.id }.distinct().size)
        assertEquals("without asking Spotify again", asked, net.requests.size)
        assertTrue(likes.state().waitingHere.isEmpty())
        assertEquals(n, likes.state().likedHere)
        assertEquals("nothing left to do the second time", 0, likes.placeMatched())
    }

    @Test fun likedSongsComeInAndNothingIsWrittenToSpotify() = runBlocking {
        // Liked here: Creep. Liked on Spotify: Blinding Lights.
        db.songs().upsert(ytSong("ytCreep0001", "Creep", "Radiohead", 239).toEntity().copy(liked = true, likedAt = 100))
        net.routes[SpotifyWeb.URL] = { 200 to likedAnswer(1, "t1" to trackData("t1", "Blinding Lights", "The Weeknd", "After Hours", 200_040)) }
        val likedHere = mutableListOf<Song>()
        val bl = ytSong("fHI8X4OXluQ", "Blinding Lights", "The Weeknd", 200)
        val likes = SpotifyLikes(api, db, importer, dir, { songs ->
            likedHere += songs
            songs.forEach { db.songs().upsert(it.toEntity(db.songs().get(it.id)).copy(liked = true, likedAt = 500)) }
        }, { emptySet() }, matchOf("Blinding Lights" to bl), pause = {}, library = { library })
        val s = likes.sync(now = 1)
        assertEquals(listOf("fHI8X4OXluQ"), likedHere.map { it.id })
        assertEquals(1, s.likedHere)
        assertEquals("nothing was liked on Spotify", 0, s.likedOnSpotify)
        assertTrue("no search, no save: only the reads were sent", net.requests.all { it.url.toString() == SpotifyWeb.URL })
        assertTrue(net.requests.none { it.method == "PUT" })
        assertTrue(net.requests.all { r -> SpotifyWeb.READS.any { op -> Buffer().also { r.body!!.writeTo(it) }.readUtf8().contains("\"operationName\":\"$op\"") } })
        // Creep, liked here, was not marked as dealt with: a later own-key connection still takes it.
        assertFalse("ytCreep0001" in s.seenVinz)
    }
}
