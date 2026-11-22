package com.vinz.music

import com.vinz.music.data.innertube.YouTubeMusic
import com.vinz.music.data.model.AlbumRef
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #383 (1.8.4.1): with "Songs everywhere", four songs of the reporter's two playlists still played their videos' sound
 * though "Play a different version" found their songs. Vinz's search match wanted the video's title exactly, brackets
 * and all, and the same artist: a film song's soundtrack tag ('(From "The Paradise")'), its dub's language ("(Telugu)")
 * and a soundtrack credited to "Various Artist" made all four miss. The rows and search results are YouTube Music's
 * (signed out, 2026-11-11: the playlist rows from PLtLEpc9CWsdfxbsiuKYw02kpbCF1mnNcS / PLEwlFF5xcXHzubwrqTqBzexAGjhl_1LoA,
 * the songs from the search Vinz makes for each).
 */
class SongsEverywhere383MatchTest {
    private fun v(id: String, title: String, sec: Int, vararg artists: String) =
        Song(id, title, artists.map { ArtistRef(it) }, durationSec = sec, isVideo = true)
    private fun s(id: String, title: String, sec: Int, album: String, vararg artists: String) =
        Song(id, title, artists.map { ArtistRef(it) }, AlbumRef(album), durationSec = sec)

    @Test fun yeshanagulaPlaysAsTheSongFromTheParadise() {
        val video = v("JqFzhcWo3EU", "Yeshanagula", 192, "Jangi Reddy", "Anirudh Ravichander", "Kasarla Shyam")
        val results = listOf(
            s("IcLdJ93eP18", "Yeshanagula (From \"The Paradise\") (Telugu)", 189, "Yeshanagula (From \"The Paradise\") (Telugu)", "Anirudh Ravichander", "Jangi Reddy", "Kasarla Shyam"),
            s("AFrAY49Ny_o", "Yesha Nagula Kattameda", 249, "Yesha Nagula Kattameda", "Veeresha Lingam"),
            s("lbEtG0ZjyJA", "Aaya Sher (From \"The Paradise\") (Telugu)", 287, "Aaya Sher (From \"The Paradise\") (Telugu)", "Anirudh Ravichander", "Jangi Reddy", "Arjun Chandy", "Kasarla Shyam"),
            s("iUT05tdJvDg", "Yeshnagula Kattameda Yesena Uyyalla", 146, "Yeshnagula Kattameda Yesena Uyyalla", "Jangi Reddy Sunitha"),
        )
        assertEquals("IcLdJ93eP18", YouTubeMusic.pickSongForVideo(video, results)?.id)
    }

    @Test fun dheemaPlaysAsItsTeluguSong() {
        val video = v("2DpqaSDuOxo", "Dheema", 229, "Anirudh Ravichander", "Sri Sai Kiran")
        val results = listOf(
            s("2ZedAROncPM", "Dheema (From \"Love Insurance Kompany\")", 236, "Dheema (From \"Love Insurance Kompany\")", "Anirudh Ravichander"),
            s("J-PLqiF8fX8", "Kalalo Kooda", 247, "Liger (Telugu) (Original Motion Picture Soundtrack)", "Tanishk Bagchi", "Sid Sriram"),
            s("5Lco4Y5fbY4", "Dheema (From \"Love Insurance Kompany (Telugu)\")", 236, "Dheema (From \"Love Insurance Kompany (Telugu)\")", "Anirudh Ravichander", "Sri Sai Kiran"),
            s("7m264AP6H7c", "Dheeme Dheeme", 259, "Kandahar", "Shamir Tandon", "Sunidhi Chauhan"),
        )
        // The Telugu playlist's video credits the Telugu lyricist: the Telugu dub, not the Tamil original of the same length.
        assertEquals("5Lco4Y5fbY4", YouTubeMusic.pickSongForVideo(video, results)?.id)
    }

    @Test fun privatePartyPlaysAsTheSarrainoduSong() {
        val video = v("_WCQkPLiNro", "Private Party (From \"Sarrainodu\")", 276, "MC Vickey")
        val results = listOf(s("hCXTPCm4O0I", "Private Party", 264, "Sarrainodu", "M.C. Vickey", "Manasi Mm"))
        assertEquals("hCXTPCm4O0I", YouTubeMusic.pickSongForVideo(video, results)?.id)
        assertEquals("Private Party MC Vickey", YouTubeMusic.songForVideoQuery(video))
    }

    @Test fun kajaluChellivaaPlaysAsTheBalupuSong() {
        val video = v("IHlrVeIIt3Y", "Kajalu Chellivaa", 245, "Junglee Music Telugu")
        val results = listOf(
            s("GzmufcvPN1g", "Kajalu Chellivaa", 235, "Balupu", "Various Artist"),
            s("LQc-wcqxfWA", "Kaatuka Kallu", 239, "Sarocharu", "Kushi Murali", "Swetha Mohan"),
            s("Kb60voO3iEc", "Yaevaindho", 244, "Balupu", "Various Artist"),
        )
        assertEquals("GzmufcvPN1g", YouTubeMusic.pickSongForVideo(video, results)?.id)
    }

    // 1.8.5: the reporter's next two playlists (PLEV4Wjn7aSFHRm-VkBxh2TJKwNyfUGqsO, PLV8MOG0oNwgPXs35owR0R1BbHwTFGRMYP), read
    // signed out on 2026-11-12: 34 of their 59 videos found no song. Rows and results are YouTube Music's.

    /** A film video is the scene's cut: up to 35 s shorter than the soundtrack's song. */
    @Test fun aFilmVideoShorterThanItsSong() {
        val peniviti = v("4oMO8IYwOos", "Peniviti", 289, "Kaala Bhairava")
        assertEquals("mnV0gUuS4j0", YouTubeMusic.pickSongForVideo(peniviti, listOf(
            s("mnV0gUuS4j0", "Peniviti", 310, "Aravindha Sametha", "Kaala Bhairava"),
            s("JeP55x8TTPQ", "Peniviti", 256, "Peniviti", "Divya Malika"),
        ))?.id)
        val chaleya = v("VAdGW7QDJiU", "Chaleya", 188, "Arijit Singh")
        assertEquals("V_jp5_VAzXk", YouTubeMusic.pickSongForVideo(chaleya, listOf(s("V_jp5_VAzXk", "Chaleya", 201, "Jawan - Hindi", "Arijit Singh", "Shilpa Rao")))?.id)
        val kaunTujhe = v("atVof3pjT-I", "Kaun Tujhe (From \"M.S.Dhoni - The Untold Story\")", 216, "Palak Muchhal")
        assertEquals("ZVyA_8rd1Ko", YouTubeMusic.pickSongForVideo(kaunTujhe, listOf(s("ZVyA_8rd1Ko", "KAUN TUJHE", 242, "M.S.DHONI - THE UNTOLD STORY", "PALAK MUCHHAL", "AMAAL MALLIK")))?.id)
        val heyIdi = v("AmGn686QCI0", "Hey Idi Nenena (From \"Solo Brathuke So Better\")", 221, "Raghuram", "Thaman S", "Sid Sriram")
        assertEquals("r4zHl1NfWD4", YouTubeMusic.pickSongForVideo(heyIdi, listOf(s("r4zHl1NfWD4", "Hey Idi Nenena (From \"Solo Brathuke So Better\")", 255, "a", "Thaman S", "Sid Sriram", "Raghuram")))?.id)
    }

    /** A short clip of the song isn't the song: "Sada Siva" 91 s for 268, "Deva Deva" 192 for 280, "Naan Gaali" 194 for 271. */
    @Test fun aClipFarShorterIsNotTheSong() {
        assertNull(YouTubeMusic.pickSongForVideo(v("agKleodoCyM", "Sada Siva", 91, "Ramajogayya Sastry", "Mani Sharma"),
            listOf(s("Zfz8HUw4Ab8", "Sada Siva", 268, "Khaleja", "Mani Sharma", "Ramesh Vinayakam", "Karunya", "Ramajogayya Sastry"))))
        assertNull(YouTubeMusic.pickSongForVideo(v("WjAPDofGg28", "Deva Deva (From \"Brahmastra\")", 192, "Amitabh Bhattacharya", "Arijit Singh", "Pritam"),
            listOf(s("zYnpURpOZvw", "Deva Deva (From \"Brahmastra\")", 280, "a", "Pritam", "Arijit Singh", "Amitabh Bhattacharya"))))
        assertNull("36 s short is too far", YouTubeMusic.pickSongForVideo(v("x", "Peniviti", 274, "Kaala Bhairava"), listOf(s("y", "Peniviti", 310, "a", "Kaala Bhairava"))))
    }

    /** One singer's name written two ways: a letter apart. */
    @Test fun anArtistsNameSpeltALetterApart() {
        val chalChal = v("F0VB5Ow1VCc", "Chal Chal", 300, "Renjith")
        assertEquals("DrAwNJy8fJE", YouTubeMusic.pickSongForVideo(chalChal, listOf(
            s("DrAwNJy8fJE", "Chal Chal", 276, "Parugu", "Ranjith"),
            s("b4mPo2D-WXc", "Chal Cha Rey", 266, "a", "Renjith"),
            s("42N6RNn1bek", "Chal Challe", 264, "Ongolu Gittha", "Narendra", "Sahithi"),
        ))?.id)
        val kathi = v("a2JPBV6Zgn8", "Kathi Lanti Pilla", 260, "Shefali Alvares")
        assertEquals("7p_CoYMy-yk", YouTubeMusic.pickSongForVideo(kathi, listOf(
            s("7p_CoYMy-yk", "Kathi Lanti Pilla", 232, "Naayak", "Thaman.S", "Shefali Alvaris"),
            s("4hcQHpS84C8", "Kathilanti Pilla", 204, "The Love Songs (telugu)", "Sathyiendra"),
        ))?.id)
        val puttene = v("meLgTwxBKLE", "Puttene Prema", 240, "Ram Miriyala")
        assertEquals("OaRiebA5Zg8", YouTubeMusic.pickSongForVideo(puttene, listOf(
            s("OaRiebA5Zg8", "Puttene Prema", 226, "Gully Rowdy", "Ram Miryala"),
            s("EA95_updxqU", "Puttene Prema BGM", 226, "Gully Rowdy - BGM", "Ram Miriyala"),
        ))?.id)
        val prema = v("tpvNtKjlf5E", "Prema Vennela", 210, "Sudarshan Ashok")
        assertEquals("M3-8opF4148", YouTubeMusic.pickSongForVideo(prema, listOf(s("M3-8opF4148", "Prema Vennela (From \"Chitralahari\")", 218, "a", "Devi Sri Prasad", "Sudharshan Ashok", "Srimani")))?.id)
    }

    @Test fun namesALetterApartOnlyWhenLongEnough() {
        assertEquals(true, YouTubeMusic.sameArtistName("renjith", "ranjith"))
        assertEquals(true, YouTubeMusic.sameArtistName("rammiriyala", "rammiryala"))
        assertEquals(true, YouTubeMusic.sameArtistName("revathy", "revathi"))
        assertEquals("short names must be the same", false, YouTubeMusic.sameArtistName("kiran", "karan"))
        assertEquals(false, YouTubeMusic.sameArtistName("muse", "mase"))
        assertEquals("two letters apart", false, YouTubeMusic.sameArtistName("ranjith", "renjeth"))
        assertEquals(false, YouTubeMusic.sameArtistName("mukesh", "suchitra"))
        assertEquals(false, YouTubeMusic.sameArtistName("", "ranjith"))
    }

    /** The video leads with a singer the song doesn't credit: any of its artists counts. */
    @Test fun anyOfTheVideosArtists() {
        val yuvakula = v("hKiVS4e5e5o", "Yuvakula", 247, "Ranjith", "Remo Fernandes", "Revathy")
        assertEquals("mUHSQahtQo8", YouTubeMusic.pickSongForVideo(yuvakula, listOf(
            s("mUHSQahtQo8", "Yuvakula", 235, "Brindaavanam", "Remo Fernandes", "Revathi"),
            s("_lvlPVUHm_8", "Yuvatha", 238, "Yuvatha", "Ranjith", "Rahul Nambiar", "Naveen"),
        ))?.id)
        val unakku = v("bui3q02NK8Y", "Unakku Thaan", 236, "Dhibu Ninan Thomas", "Santhosh Narayanan", "Dhvani Kailas")
        assertEquals("s41jPtKYxbE", YouTubeMusic.pickSongForVideo(unakku, listOf(
            s("s41jPtKYxbE", "Unakku Thaan (From \"Chithha\")", 217, "a", "Santhosh Narayanan", "Dhvani Kailas", "Vivek"),
            s("yVd5JPDb33E", "Unakku Thaan", 65, "Unforgettables", "Nirthi", "Britto Jude"),
        ))?.id)
    }

    /** A film label's title: the song's name is before the first bar, without "Video Song". */
    @Test fun aLabelsTitleWithBars() {
        val video = v("XeGdY8RoxQY", "Chamkeela Angeelesi - Video Song | Dasara | Nani,Keerthy Suresh | Santhosh Narayanan| Srikanth Odela", 211,
            "Aparna Harikumar", "Santhosh Hariharan", "Santhosh Narayanan")
        assertEquals("Chamkeela Angeelesi Aparna Harikumar", YouTubeMusic.songForVideoQuery(video))
        assertEquals("yY8oOxMPGqo", YouTubeMusic.pickSongForVideo(video, listOf(
            s("yY8oOxMPGqo", "Chamkeela Angeelesi", 204, "Chamkeela Angeelesi (From \"Dasara\") (Telugu)", "Santhosh Narayanan", "Ram Miriyala", "Dhee"),
            s("0d0GdjwiDGc", "Chamkeeli Bushirt Mein", 204, "Chamkeeli Bushirt Mein (From \"Dasara\") (Hindi)", "Santhosh Narayanan"),
            s("Fu_hMPtizkU", "Chamkeela Angeelesi - EDM", 150, "Chamkeela Angeelesi - EDM", "Aravind Polo", "Santhosh Narayanan"),
        ))?.id)
        val full = v("fFWL-UrjOkU", "Naalo Chilipi Kala Full Video Song | Lover | Raj Tarun, Riddhi Kumar | Yazin Nizar | Sai Kartheek", 244, "Jubin Nautiyal")
        assertEquals("Naalo Chilipi Kala Jubin Nautiyal", YouTubeMusic.songForVideoQuery(full))
        // A song whose name ends in "Song" keeps it when there are no bars.
        assertEquals("The Lazy Song Bruno Mars", YouTubeMusic.songForVideoQuery(v("x", "The Lazy Song", 200, "Bruno Mars")))
    }

    /**
     * Review (1.8.5): with a bar, a last word like "Song" or "Audio" was taken for the label's even when it's half the
     * song's name: "Love Song | Artist" was searched as "Love". A label's tail after a dash still goes.
     */
    @Test fun aLabelTailNeverTakesHalfTheName() {
        assertEquals("Love Song Adele", YouTubeMusic.songForVideoQuery(v("x", "Love Song | Adele | 25", 200, "Adele")))
        assertEquals("Radio Audio Band", YouTubeMusic.songForVideoQuery(v("x", "Radio Audio | Band", 200, "Band")))
        assertEquals("y", YouTubeMusic.pickSongForVideo(v("x", "Love Song | Adele | 25", 200, "Adele"), listOf(
            s("y", "Love Song", 199, "25", "Adele"),
        ))?.id)
        // Two words or more left, a dash before the label's words, or a label's own word: stripped as before.
        assertEquals("Peniviti Kaala Bhairava", YouTubeMusic.songForVideoQuery(v("x", "Peniviti - Lyrical | Aravinda Sametha | NTR", 289, "Kaala Bhairava")))
        assertEquals("Peniviti Kaala Bhairava", YouTubeMusic.songForVideoQuery(v("x", "Peniviti Lyrical | Aravinda Sametha | NTR", 289, "Kaala Bhairava")))
        assertEquals("Kaun Tujhe Palak Muchhal", YouTubeMusic.songForVideoQuery(v("x", "Kaun Tujhe Full Video Song | M.S. Dhoni", 216, "Palak Muchhal")))
        // "Kesariya Song | Brahmastra" is searched as written, and still found as the song "Kesariya".
        val kesariya = v("x", "Kesariya Song | Brahmastra | Ranbir Kapoor, Alia Bhatt", 268, "Arijit Singh")
        assertEquals("Kesariya Song Arijit Singh", YouTubeMusic.songForVideoQuery(kesariya))
        assertEquals("k", YouTubeMusic.pickSongForVideo(kesariya, listOf(s("k", "Kesariya (From \"Brahmastra\")", 268, "Brahmastra", "Pritam", "Arijit Singh")))?.id)
    }

    /** "(feat. …)" on the video or on the song is the same recording. */
    @Test fun featuredNamesInTheTitle() {
        val video = v("AXSm49NGkg8", "Mastaaru Mastaaru (feat. Dhanush & Samyuktha Menon)", 196, "G.V. Prakash Kumar")
        assertEquals("y", YouTubeMusic.pickSongForVideo(video, listOf(s("y", "Mastaaru Mastaaru", 199, "Sir", "G.V. Prakash Kumar", "Shweta Mohan")))?.id)
        assertEquals("z", YouTubeMusic.pickSongForVideo(v("x", "Stay", 141, "The Kid LAROI"), listOf(s("z", "Stay (feat. Justin Bieber)", 141, "a", "The Kid LAROI")))?.id)
    }

    /**
     * Not matched by names (as [anotherArtistsSongStillDoesNotCount]): the video credits someone the song doesn't name
     * at all ("Dandiya India": Mukesh / Suchitra; "Sairo Sairo": Renjith / Rahul Nambiar, Naveen; "Sailaja Sailaja": the
     * lyricist / the composer and singer). Without a shared name, and nothing else to confirm it, a cover's video would
     * become the original: it stays a video, also when its title names no film or album.
     */
    @Test fun aVideoCreditingNobodyTheSongNamesStaysAVideo() {
        val dandiya = v("OgyB7CspkQY", "Dandiya India", 302, "Mukesh")
        val dandiyaSong = listOf(s("cYr59BxBUN4", "Dandiya India", 282, "Oosaravelli", "Suchitra"))
        assertNull(YouTubeMusic.pickSongForVideo(dandiya, dandiyaSong))
        assertNull("the row's own title names no film", YouTubeMusic.pickSongForVideo(dandiya, dandiyaSong) { YouTubeMusic.namesRelease(dandiya.title, it) })
        val sailaja = v("teaO8hzOvsI", "Sailaja Sailaja", 261, "Bhaskara Bhatla")
        val sailajaSong = listOf(s("gsE1_8QIZa4", "Sailaja Sailaja", 234, "Nenu Sailaja", "Devi Sri Prasad", "Sagar"))
        assertNull(YouTubeMusic.pickSongForVideo(sailaja, sailajaSong))
        assertNull("only its own name, twice", YouTubeMusic.pickSongForVideo(sailaja, sailajaSong) { YouTubeMusic.namesRelease("Sailaja Sailaja Full Song", it) })
        assertNull("another film named", YouTubeMusic.pickSongForVideo(sailaja, sailajaSong) { YouTubeMusic.namesRelease("Sailaja Sailaja Full Song || Shailaja Reddy Alludu Songs", it) })
    }

    // 1.8.7 (plan 3.1): the three nsk-47 named after 1.8.5. Rows, search results, album pages and `player` answers read
    // signed out on 2026-11-14 from PLEV4Wjn7aSFHRm-VkBxh2TJKwNyfUGqsO. Neither confirmation (a) nor (b) of the plan holds
    // for them: the songs' own video search finds another upload (Dandiya: Suchitra's I3AHMObuatU) or none, and their album
    // pages list the songs, not these videos. What does: the video's full title, which the playlist row cuts to the
    // song's name, names the film.

    private fun fullTitle(id: String): String {
        val text = javaClass.getResource("/songs383/$id-player.json")!!.readText()
        val player = com.vinz.music.data.innertube.InnerTube.json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        return YouTubeMusic.fullVideoTitleOf(player)!!
    }

    private fun picked(video: Song, results: List<Song>): String? {
        val full = fullTitle(video.id)
        assertEquals("nothing by name", null, YouTubeMusic.pickSongForVideo(video, results))
        assertEquals("a full title is worth asking for", true, YouTubeMusic.waitsOnConfirmation(video, results))
        return YouTubeMusic.pickSongForVideo(video, results) { YouTubeMusic.namesRelease(full, it) }?.id
    }

    @Test fun theFullTitleIsTheWatchPagesNotTheRows() {
        assertEquals("Oosaravelli Telugu Movie || Dandiya India Song With Lyrics || Jr.Ntr,Tamanna,", fullTitle("OgyB7CspkQY"))
        assertEquals("Sairo Sairo Full Song | Baadshah Movie | Jr. NTR, Kajal Agarwal || Thaman S", fullTitle("fIgXoqQ0c_s"))
    }

    @Test fun dandiyaIndiaPlaysAsTheOosaravelliSong() {
        val video = v("OgyB7CspkQY", "Dandiya India", 302, "Mukesh")
        val results = listOf(
            Song("cYr59BxBUN4", "Dandiya India", listOf(ArtistRef("Suchitra")), AlbumRef("Oosaravelli", "MPREb_Ya4HVVt4xrE"), durationSec = 282),
            s("6jEyVZKdoO0", "Dholi Taaro Dhol Vaaghe (41 Non Stop)", 3629, "Dholi Taaro Dhol Vaaghe", "Anuradha Paudwal", "Kavita Paudwal", "Nitin Mukesh"),
            s("wVXTfjZaSqM", "Non Stop Bollywood Dandiya Garbe Ki Raat Hai 2014", 4053, "Non Stop Bollywood Dandiya 2014 (Garbe Ki Raat Hai)", "Manish J Tipu", "Arijit Singh"),
            s("euZGBtdCxns", "Jharkhand kar Chori", 320, "Holi Mei Tor Ghar", "Mukesh"),
        )
        assertEquals("cYr59BxBUN4", picked(video, results))
    }

    @Test fun sairoSairoPlaysAsTheBaadshahSongNotAdhrustams() {
        val video = v("fIgXoqQ0c_s", "Sairo Sairo", 257, "Renjith")
        val results = listOf(
            s("94JuL_OdJ_g", "Sairo Sairo", 231, "Baadshah", "Rahul Nambiar", "Naveen"),
            s("us_GvpcAEBw", "Sairo Sairo", 309, "Adhrustam", "Kiran", "Devan"),
            s("B5qbQuvDGa4", "Diamond Girl", 239, "Baadshah", "Suchitra"),
            s("kETqj1rD4_w", "Sairo", 197, "Jackpot (Telugu) (Original Motion Picture Soundtrack)", "Vishal Chandrashekhar", "M.M. Srilekha", "Uma Neha"),
            s("_2LDWDtkkn8", "Anbe Nee", 259, "Stalin", "Ranjith"),
        )
        assertEquals("94JuL_OdJ_g", picked(video, results))
    }

    @Test fun sailajaSailajaPlaysAsTheNenuSailajaSong() {
        val video = v("teaO8hzOvsI", "Sailaja Sailaja", 261, "Bhaskara Bhatla")
        val results = listOf(
            s("gsE1_8QIZa4", "Sailaja Sailaja", 234, "Nenu Sailaja", "Devi Sri Prasad", "Sagar"),
            s("_W2rFMDxg0o", "Crazy Feeling", 210, "Nenu Sailaja", "Ramajogayya Sastry", "Devi Sri Prasad", "Prudhvi Chandra"),
            s("tf4RVw2iWPA", "Sailaja", 362, "Sailaja", "Shiva Alijarla"),
            s("9g8y_Nii7XY", "Sailu Sailu Sailaja", 282, "Pilla Perucheppu Maisamma", "Vadlakonda Anil Kumar"),
        )
        assertEquals("gsE1_8QIZa4", picked(video, results))
    }

    /** The same title by another artist, its title naming another release (or a cover of it), stays a video. */
    @Test fun theSameTitleByAnotherArtistNeedsItsRelease() {
        val video = v("x", "Dandiya India", 300, "Someone")
        val other = listOf(s("y", "Dandiya India", 290, "Dandiya Nights", "Another Singer"))
        assertNull(YouTubeMusic.pickSongForVideo(video, other) { YouTubeMusic.namesRelease("Oosaravelli Telugu Movie || Dandiya India Song", it) })
        val song = listOf(s("z", "Dandiya India", 282, "Oosaravelli", "Suchitra"))
        assertNull("a cover", YouTubeMusic.pickSongForVideo(video, song) { YouTubeMusic.namesRelease("Dandiya India cover | Oosaravelli", it) })
        assertNull("the length still counts", YouTubeMusic.pickSongForVideo(v("x", "Dandiya India", 120, "Someone"), song) { YouTubeMusic.namesRelease("Oosaravelli || Dandiya India", it) })
        // A single's album is the song: the film is its soundtrack tag's.
        val single = listOf(s("p", "Yeshanagula (From \"The Paradise\") (Telugu)", 189, "Yeshanagula (From \"The Paradise\") (Telugu)", "Anirudh Ravichander"))
        assertEquals("p", YouTubeMusic.pickSongForVideo(v("q", "Yeshanagula", 192, "A Label"), single) { YouTubeMusic.namesRelease("Yeshanagula Video Song | The Paradise | Nani", it) }?.id)
        assertNull(YouTubeMusic.pickSongForVideo(v("q", "Yeshanagula", 192, "A Label"), single) { YouTubeMusic.namesRelease("Yeshanagula Video Song | Nani", it) })
    }

    /** A song found by name never needs the request for the full title. */
    @Test fun noFullTitleAskedWhenANameMatches() {
        val video = v("JqFzhcWo3EU", "Yeshanagula", 192, "Jangi Reddy")
        val results = listOf(s("IcLdJ93eP18", "Yeshanagula (From \"The Paradise\") (Telugu)", 189, "a", "Anirudh Ravichander", "Jangi Reddy"))
        assertEquals(false, YouTubeMusic.waitsOnConfirmation(video, results))
        assertEquals(false, YouTubeMusic.waitsOnConfirmation(v("x", "Another Song", 200, "Someone"), results))
    }

    // Plan 3.3 (#154's last gap): an album page that lists videos, with its playlist a track longer or shorter.

    @Test fun anAlbumWithOneExtraTrackStillPlaysItsSongs() {
        val listed = listOf(
            Song("v1", "Brathakaali", durationSec = 330, isVideo = true),
            Song("s2", "Nenante Naaku", durationSec = 244),
            Song("v3", "Dandiya India", durationSec = 300, isVideo = true),
            Song("v4", "Oosaravelli", durationSec = 150, isVideo = true),
        )
        val own = listOf(
            s("p1", "Brathakaali", 327, "Oosaravelli", "Devi Sri Prasad"),
            s("s2", "Nenante Naaku", 244, "Oosaravelli", "Adnan Sami"),
            s("bonus", "Brathakaali (Remix)", 300, "Oosaravelli", "Devi Sri Prasad"),
            s("p3", "Dandiya India", 282, "Oosaravelli", "Suchitra"),
            s("p4", "Oosaravelli", 150, "Oosaravelli", "Ujjayinee Roy"),
        )
        val pairs = YouTubeMusic.pairAlbumTracks(listed, own)
        assertEquals(mapOf(0 to "p1", 2 to "p3", 3 to "p4"), pairs.mapValues { it.value.first.id })
        assertEquals("v3", pairs[2]?.second)
        // A video with no song of its title on the playlist stays a video; a song is taken once.
        val twice = listOf(Song("v1", "Brathakaali", durationSec = 330, isVideo = true), Song("v1b", "Brathakaali", durationSec = 330, isVideo = true), Song("v9", "Not On It", durationSec = 200, isVideo = true))
        assertEquals(mapOf(0 to "p1"), YouTubeMusic.pairAlbumTracks(twice, own).mapValues { it.value.first.id })
    }

    @Test fun anAlbumOfTheSameLengthStillPairsByPlace() {
        val listed = listOf(Song("v1", "Intro", durationSec = 600, isVideo = true), Song("v2", "Song", durationSec = 200, isVideo = true))
        val own = listOf(s("p1", "Intro", 60, "a", "X"), s("p2", "Other", 200, "a", "X"))
        // By place: the title must agree there, and the length doesn't matter (an 8-minute video for a 5-minute song).
        assertEquals(mapOf(0 to "p1"), YouTubeMusic.pairAlbumTracks(listed, own).mapValues { it.value.first.id })
    }

    // What still keeps recordings apart.

    @Test fun anotherDubIsNotTheSong() {
        val video = v("x", "Dheema (Tamil)", 236, "Anirudh Ravichander")
        val results = listOf(s("t", "Dheema (From \"Love Insurance Kompany (Telugu)\")", 236, "a", "Anirudh Ravichander"))
        assertNull(YouTubeMusic.pickSongForVideo(video, results))
    }

    @Test fun otherBracketsStillTellRecordingsApart() {
        // #113: a live album's track isn't the studio song.
        val video = v("x", "Nos siguen pegando abajo", 250, "Charly García")
        val results = listOf(s("y", "Nos siguen pegando abajo (Pecado mortal)", 250, "a", "Charly García"))
        assertNull(YouTubeMusic.pickSongForVideo(video, results))
    }

    @Test fun variousArtistsOnlyWhenItsTheOneSongOfThatTitle() {
        val video = v("x", "Yesterday", 125, "Some Label")
        val results = listOf(
            s("a", "Yesterday", 123, "Hits 1", "Various Artists"),
            s("b", "Yesterday", 126, "Hits 2", "Various Artists"),
        )
        assertNull(YouTubeMusic.pickSongForVideo(video, results))
        assertNull("nor of another length", YouTubeMusic.pickSongForVideo(video, listOf(s("a", "Yesterday", 300, "Hits", "Various Artists"))))
    }

    @Test fun anotherArtistsSongStillDoesNotCount() {
        val video = v("x", "Kajalu Chellivaa", 245, "Junglee Music Telugu")
        assertNull(YouTubeMusic.pickSongForVideo(video, listOf(s("a", "Kajalu Chellivaa", 240, "b", "Someone Else"))))
    }
}
