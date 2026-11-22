package com.vinz.music

import com.vinz.music.data.SongVersions
import com.vinz.music.data.meta.PairSource
import com.vinz.music.data.meta.PlaysAs
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import com.vinz.music.download.readStandIns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 1.8.7 §3.2: an install from before [PlaysAs] keeps what it had. The listener's picks and the download stand-ins are
 * read where they always were (no migration): a pick kept before 1.8.1.1 (no version details) and a stand-in from 1.8.1
 * are honoured as they're found.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PlaysAsExistingInstallTest {
    private val video = Song("vidV0000001", "Night Drive (Official Video)", listOf(ArtistRef("TheBandVEVO")), isVideo = true)
    private val song = Song("sngS0000001", "Night Drive", listOf(ArtistRef("The Band")))

    @Test fun picksAndStandInsFromBeforeAreHonoured() {
        val app = RuntimeEnvironment.getApplication()
        // As 1.7.9 wrote a pick ("id<TAB>label", no version details) and 1.8.1 a stand-in (video id -> song id).
        val versionPrefs = app.getSharedPreferences("song_versions_old_install", 0).apply { edit().clear().putString(video.id, "pckP0000001\tSomeone · 3:30").commit() }
        val standInPrefs = app.getSharedPreferences("download_standins_old_install", 0).apply { edit().clear().putString("vidV0000002", song.id).commit() }
        val versions = SongVersions(versionPrefs)
        val plays = PlaysAs(null, CoroutineScope(Job()), pickOf = { versions.picks.value[it] }, io = Dispatchers.Unconfined)
        plays.setDownloaded(readStandIns(standInPrefs)) { null }

        // The old pick still plays in the row's place, the row staying itself (shown as itself: no details were kept).
        val a = plays.answer(video)
        assertEquals(PairSource.LISTENER_PICK, a.source)
        assertEquals("pckP0000001", a.sound)
        assertEquals(video, a.queued)
        assertEquals(video, a.shown)
        // ...over a pairing learned since.
        plays.learn(video.id, song, PairSource.ALBUM_PAGE)
        assertEquals("pckP0000001", plays.answer(video).sound)

        // The old stand-in still marks its row downloaded, whatever "Songs, not videos" is set to now.
        assertEquals(song.id, plays.keptAs(everywhere = false)["vidV0000002"])
        assertEquals(song.id, plays.keptAs(everywhere = true)["vidV0000002"])
        assertNull("a picked row downloads as itself", plays.keptAs(everywhere = true)[video.id])
        // Knowing only the song's id, it plays as the song once that song is known (here, from what was learned).
        plays.learn("vidV0000002", song, PairSource.VIDEO_MATCH)
        assertEquals(song, plays.songFor(video.copy(id = "vidV0000002")))
        assertEquals(PairSource.DOWNLOAD, plays.answer(video.copy(id = "vidV0000002")).source)
        assertTrue(plays.picked(video.id))
    }
}
