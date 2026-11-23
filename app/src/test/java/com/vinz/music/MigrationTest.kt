package com.vinz.music

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.prism.music.data.db.PrismDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every older database version upgrades to the current one without losing the listener's history. Each version's
 * schema is exported by Room into app/schemas; this builds a database exactly as that version left it (with a song,
 * a like and plays in it), opens it with the app's migrations, and checks the rows survived. Room itself refuses to
 * open it if a migration's result doesn't match the current schema.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationTest {
    private val schemaDir = listOf("schemas", "app/schemas").map { File(it, "com.prism.music.data.db.PrismDatabase") }.first { it.isDirectory }
    private val versions = schemaDir.listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.sorted()

    @Test
    fun everyVersionUpgradesKeepingHistory() {
        val latest = versions.last()
        for (from in versions.dropLast(1)) {
            val context = RuntimeEnvironment.getApplication()
            val name = "migrate-from-$from.db"
            context.deleteDatabase(name)
            createAt(context.getDatabasePath(name), from)
            val db = Room.databaseBuilder(context, PrismDatabase::class.java, name)
                .addMigrations(*PrismDatabase.MIGRATIONS)
                .allowMainThreadQueries()
                .build()
            runBlocking {
                val song = db.songs().get("song1")
                assertEquals("v$from: song kept", "Title", song?.title)
                assertTrue("v$from: like kept", song!!.liked)
                assertEquals("v$from: plays kept", 2, db.plays().count(0, Long.MAX_VALUE))
                assertEquals("v$from: play time kept", 200_000L, db.plays().totalMs(0, Long.MAX_VALUE))
                assertEquals("v$from: top song", "song1", db.plays().topSongs(0, Long.MAX_VALUE, 5).single().songId)
            }
            assertEquals(latest, db.openHelper.readableDatabase.version)
            db.close()
        }
    }

    /**
     * v8: songs get their main artist. A joint credit counts for its first artist (not as an artist of its own), a
     * file without an artist ("Unknown artist") counts for none, and genres guessed for such files go.
     */
    @Test
    fun v8CountsSongsUnderTheirMainArtist() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migrate-v8-artists.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        createAt(file, 7)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            fun song(id: String, artist: String, artistId: String?) = db.insert("songs", null, ContentValues().apply {
                put("id", id); put("title", "T $id"); put("artistName", artist); put("artistId", artistId)
                put("durationSec", 200); put("isVideo", 0); put("liked", 0); put("likedAt", 0L); put("lastPlayed", 0L); put("smartDownload", 0)
            })
            fun play(id: String, ms: Long) = db.insert("play_events", null, ContentValues().apply {
                put("songId", id); put("timestamp", 3_000L); put("playedMs", ms)
            })
            song("joint", "The Kid LAROI, Justin Bieber", "UCkid"); play("joint", 150_000)
            song("solo", "The Kid LAROI", "UCkid"); play("solo", 100_000)
            song("local:7", "Unknown artist", null); play("local:7", 500_000)
            db.insert("genres", null, ContentValues().apply { put("key", "song2:local:7"); put("genre", "Dance & electronic"); put("fetchedAt", 1L) })
            db.insert("genres", null, ContentValues().apply { put("key", "song2:joint"); put("genre", "Pop"); put("fetchedAt", 1L) })
        }
        val db = Room.databaseBuilder(context, PrismDatabase::class.java, name)
            .addMigrations(*PrismDatabase.MIGRATIONS).allowMainThreadQueries().build()
        runBlocking {
            val artists = db.plays().topArtists(0, Long.MAX_VALUE, 10)
            assertEquals("joint credit counts for its main artist", listOf("The Kid LAROI", "Artist"), artists.map { it.artistName })
            assertEquals(250_000L, artists.first().ms)
            assertEquals("no 'Unknown artist'", 2, db.plays().artistCount(0, Long.MAX_VALUE))
            assertTrue("a file without an artist has none", db.songs().get("local:7")!!.toSong().artists.isEmpty())
            assertEquals("Unknown artist", db.songs().get("local:7")!!.toSong().artistText)
            assertEquals(listOf("The Kid LAROI", "Justin Bieber"), db.songs().get("joint")!!.toSong().artists.map { it.name })
            assertEquals("the guessed genre went", null, db.meta().genre("song2:local:7"))
            assertEquals("real genres stay", "Pop", db.meta().genre("song2:joint")?.genre)
        }
        db.close()
    }

    /**
     * v9 (1.8.1): imported histories get their own tables and the counted_plays view, and Vinz's plays read through
     * the view exactly as from play_events.
     */
    @Test
    fun v9AddsImportedHistoryApart() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migrate-v9-imports.db"
        context.deleteDatabase(name)
        createAt(context.getDatabasePath(name), 8)
        val db = Room.databaseBuilder(context, PrismDatabase::class.java, name)
            .addMigrations(*PrismDatabase.MIGRATIONS).allowMainThreadQueries().build()
        runBlocking {
            val vinz = listOf("vinz")
            assertEquals(db.plays().totalMs(0, Long.MAX_VALUE), db.counted().totalMs(vinz, 0, Long.MAX_VALUE))
            assertEquals(db.plays().topSongs(0, Long.MAX_VALUE, 5), db.counted().topSongs(vinz, 0, Long.MAX_VALUE, 5))
            db.imports().insertPlays(listOf(com.prism.music.data.db.ImportedPlayEntity(
                origin = "spotify", timestamp = 5_000L, playedMs = 60_000, title = "Title", artist = "Artist", songKey = "title|artist", matchedId = "song1",
            )))
            assertEquals("not counted unless asked", 200_000L, db.counted().totalMs(vinz, 0, Long.MAX_VALUE))
            assertEquals(260_000L, db.counted().totalMs(vinz + "spotify", 0, Long.MAX_VALUE))
            assertEquals(3, db.counted().topSongs(vinz + "spotify", 0, Long.MAX_VALUE, 5).single().plays)
        }
        db.close()
    }

    /** The Vinz v9 export is taken verbatim from main. The v12 fixture is the genuine upstream Room export;
     * its entities and view match the PR's v12 source. Room validates the resulting schema when opened. */
    @Test
    fun authenticVinzV9AndUpstreamV12PreserveHistoryAndUserChoices() {
        val context = RuntimeEnvironment.getApplication()
        val actualV9 = File(schemaDir.parentFile, "com.vinz.music.data.db.VinzDatabase/9.json")
        for (from in listOf(9, 12)) {
            val name = "migrate-real-v$from.db"
            context.deleteDatabase(name)
            val file = context.getDatabasePath(name)
            createAt(file, from, if (from == 9) actualV9 else File(schemaDir, "12.json"))
            val tables = listOf("songs", "play_events", "imported_plays", "import_matches") +
                if (from == 12) listOf("song_traits", "song_tags", "canvas_clips") else emptyList()
            fun rows(cursor: android.database.Cursor): List<List<String?>> = cursor.use {
                buildList { while (it.moveToNext()) add((0 until it.columnCount).map { n -> it.getString(n) }) }
            }
            val before = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("""INSERT INTO imported_plays (origin,timestamp,playedMs,skipped,estimated,title,artist,album,sourceId,songKey,matchedId)
                    VALUES ('spotify',5000,60000,0,0,'Imported','Artist','Album','source','imported|artist','song1'),
                           ('lastfm',6000,5000,1,1,'Short','Artist',NULL,NULL,'short|artist',NULL)""")
                old.execSQL("""INSERT INTO import_matches (songKey,title,artist,album,durationMs,atLeastMs,sourceId,isrc,youtubeId,status,matchedId,candidates,checkedAt,tries)
                    VALUES ('imported|artist','Imported','Artist','Album',200000,60000,'source','isrc','youtube','matched','song1',NULL,123,2)""")
                if (from == 12) {
                    old.execSQL("INSERT INTO song_tags VALUES ('favorite','song1',123)")
                    old.execSQL("INSERT INTO song_traits VALUES ('song1',120.0,1,'C','8A',-9.0,0.6,1,123)")
                    old.execSQL("""INSERT INTO canvas_clips VALUES ('song1','video1','', 'OMV',1080,137,10,20,
                        0.5,0.0,0.1,0.0,0.1,'10,20',1,1,'old-video','cover',123,'')""")
                }
                tables.associateWith { rows(old.rawQuery("SELECT * FROM `$it` ORDER BY rowid", null)) }
            }
            val db = Room.databaseBuilder(context, PrismDatabase::class.java, name)
                .addMigrations(*PrismDatabase.MIGRATIONS).allowMainThreadQueries().build()
            val migrated = db.openHelper.writableDatabase // Room validates the entire final schema here.
            assertEquals(13, migrated.version)
            for (table in tables) assertEquals("v$from $table rows unchanged", before[table], rows(migrated.query("SELECT * FROM `$table` ORDER BY rowid")))
            runBlocking {
                assertEquals(2, db.counted().count(listOf("vinz"), 0, Long.MAX_VALUE))
                assertEquals(200_000L, db.counted().totalMs(listOf("vinz"), 0, Long.MAX_VALUE))
                assertEquals(3, db.counted().count(listOf("vinz", "spotify", "lastfm"), 0, Long.MAX_VALUE))
                assertEquals(260_000L, db.counted().totalMs(listOf("vinz", "spotify", "lastfm"), 0, Long.MAX_VALUE))
            }
            db.close()
        }
    }

    @Test
    fun unknownDowngradeFailsWithoutDeletingData() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-unknown-downgrade.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        createAt(file, 12)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 14 }
        val db = Room.databaseBuilder(context, PrismDatabase::class.java, name)
            .addMigrations(*PrismDatabase.MIGRATIONS).allowMainThreadQueries().build()
        val failure = runCatching { db.openHelper.writableDatabase }.exceptionOrNull()
        db.close()
        assertTrue("unsupported downgrade must fail", failure != null)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { old ->
            assertEquals(14, old.version)
            old.rawQuery("SELECT COUNT(*) FROM songs WHERE id = 'song1' AND liked = 1", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            old.rawQuery("SELECT COUNT(*) FROM play_events", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(2, it.getInt(0))
            }
        }
    }

    /** v10 (1.8.3): what's measured of songs gets its own table, empty until the copy from the prefs fills it. */
    @Test
    fun v10AddsSongTraits() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migrate-v10-traits.db"
        context.deleteDatabase(name)
        createAt(context.getDatabasePath(name), 9)
        val db = Room.databaseBuilder(context, PrismDatabase::class.java, name)
            .addMigrations(*PrismDatabase.MIGRATIONS).allowMainThreadQueries().build()
        runBlocking {
            assertEquals(0, db.traits().count())
            db.traits().put(com.prism.music.data.db.SongTraitsEntity("song1", 120.0, true, null, "8A", -9f, 0.6f, whole = true, measuredAt = 1L))
            assertEquals(120.0, db.traits().get("song1")!!.bpm!!, 1e-9)
            assertEquals("history kept", 2, db.plays().count(0, Long.MAX_VALUE))
        }
        db.close()
    }

    /** v11 (1.8.3): the listener's tags on songs, a table of their own. */
    @Test
    fun v11AddsSongTags() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migrate-v11-tags.db"
        context.deleteDatabase(name)
        createAt(context.getDatabasePath(name), 10)
        val db = Room.databaseBuilder(context, PrismDatabase::class.java, name)
            .addMigrations(*PrismDatabase.MIGRATIONS).allowMainThreadQueries().build()
        runBlocking {
            assertEquals(emptyList<String>(), db.tags().names())
            db.tags().add(listOf(com.prism.music.data.db.SongTagEntity("gym", "song1", 1L)))
            assertEquals(listOf("gym"), db.tags().names())
            assertEquals("history kept", 2, db.plays().count(0, Long.MAX_VALUE))
        }
        db.close()
    }

    /** v12 (1.8.6): Canvas loops cut from music videos, a row a song; the listener's choices outlive a clear-out. */
    @Test
    fun v12AddsCanvasClips() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migrate-v12-canvas.db"
        context.deleteDatabase(name)
        createAt(context.getDatabasePath(name), 11)
        val db = Room.databaseBuilder(context, PrismDatabase::class.java, name)
            .addMigrations(*PrismDatabase.MIGRATIONS).allowMainThreadQueries().build()
        fun row(id: String, video: String?, picked: Boolean = false, prefer: String? = null, rejected: String = "") = com.prism.music.data.db.CanvasClipEntity(
            id, video, if (video == null) "STILL" else "", "OMV", 1080, 137, 64_898, 71_900, 0.5f, 0f, 0.12f, 0f, 0.12f, "30000,90000",
            refined = true, picked = picked, rejectedVideos = rejected, prefer = prefer, checkedAt = 1L,
        )
        runBlocking {
            assertEquals(0, db.canvasClips().count())
            db.canvasClips().put(row("guess", "v1"))
            db.canvasClips().put(row("none", null))
            db.canvasClips().put(row("picked", "v2", picked = true))
            db.canvasClips().put(row("prefers", "v3", prefer = "cover"))
            db.canvasClips().put(row("rejected", null, rejected = "v9"))
            assertEquals("only rows with a loop count", 3, db.canvasClips().count())
            assertEquals(0.12f, db.canvasClips().get("guess")!!.barTop, 1e-6f)
            db.canvasClips().clearGuesses()
            assertEquals(null, db.canvasClips().get("guess"))
            assertEquals(null, db.canvasClips().get("none"))
            assertEquals("v2", db.canvasClips().get("picked")!!.videoId)
            assertEquals("cover", db.canvasClips().get("prefers")!!.prefer)
            assertEquals("v9", db.canvasClips().get("rejected")!!.rejectedVideos)
            assertEquals("history kept", 2, db.plays().count(0, Long.MAX_VALUE))
        }
        db.close()
    }

    @Test
    fun currentSchemaIsExported() {
        // A version bump without its exported schema would leave the next migration untestable.
        val fresh = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PrismDatabase::class.java).allowMainThreadQueries().build()
        val current = fresh.openHelper.writableDatabase.version
        fresh.close()
        assertEquals(current, versions.last())
        assertEquals("one migration per version step", current - 1, PrismDatabase.MIGRATIONS.size)
        // The CI test-results artifact carries Room's generated export so it can be checked in verbatim.
        println("ROOM_SCHEMA_EXPORT_BEGIN\n${File(schemaDir, "$current.json").readText()}\nROOM_SCHEMA_EXPORT_END")
    }

    /** Builds the database as version [version] of Vinz created it, from the exported schema. */
    private fun createAt(file: File, version: Int, schemaFile: File = File(schemaDir, "$version.json")) {
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject["database"]!!.jsonObject
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        for (entity in schema["entities"]!!.jsonArray) {
            val e = entity.jsonObject
            val table = e.str("tableName")
            db.execSQL(e.str("createSql").replace("\${TABLE_NAME}", table))
            e["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject.str("createSql").replace("\${TABLE_NAME}", table)) }
        }
        // Views too (v9's counted_plays): a database from 9 on has them, and Room checks them after a migration.
        schema["views"]?.jsonArray?.forEach { v ->
            val view = v.jsonObject
            db.execSQL(view.str("createSql").replace("\${VIEW_NAME}", view.str("viewName")))
        }
        schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = schema["version"]!!.jsonPrimitive.int
        db.insert("songs", null, ContentValues().apply {
            put("id", "song1"); put("title", "Title"); put("artistName", "Artist"); put("artistId", "UC1")
            put("durationSec", 200); put("isVideo", 0); put("liked", 1); put("likedAt", 5L); put("lastPlayed", 10L); put("smartDownload", 0)
        })
        for (t in listOf(1_000L, 2_000L)) db.insert("play_events", null, ContentValues().apply {
            put("songId", "song1"); put("timestamp", t); put("playedMs", 100_000L)
        })
        db.close()
    }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
}
