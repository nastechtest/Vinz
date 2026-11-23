package com.vinz.music

import com.vinz.music.data.ModelStore
import com.prism.music.data.db.RomanizedDao
import com.prism.music.data.db.RomanizedEntity
import com.vinz.music.data.lyrics.LyricLine
import com.vinz.music.data.lyrics.LyricWord
import com.vinz.music.data.lyrics.Romanizer
import com.vinz.music.data.lyrics.roman.Japanese
import com.vinz.music.data.lyrics.roman.JapaneseKanji
import com.vinz.music.data.lyrics.roman.Script
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.URLDecoder

/** Main-branch lyric behavior and original-text title spelling. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class JapaneseKanjiTest {
    private val saved = HashMap<String, RomanizedEntity>()
    private val dao = object : RomanizedDao {
        override suspend fun get(key: String) = saved[key]
        override suspend fun put(e: RomanizedEntity) { saved[e.key] = e }
    }
    private val asked = ArrayList<Pair<String, List<String>>>()

    /** Stands in for Google: says what it was asked, and answers each line with "roman". */
    private val fakeGoogle = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req = chain.request()
        val body = Buffer().also { req.body!!.writeTo(it) }.readUtf8().substringAfter("q=")
        val lines = URLDecoder.decode(body, "UTF-8").split("\n")
        asked += req.url.queryParameter("sl")!! to lines
        val json = """[[[null,null,null,"${lines.joinToString("\\n") { "roman" }}"]]]"""
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(json.toResponseBody("application/json".toMediaType())).build()
    }).build()

    private fun romanizer(http: OkHttpClient, reader: Japanese? = null) =
        Romanizer(http, dao, ModelStore(RuntimeEnvironment.getApplication(), http), CoroutineScope(Dispatchers.Default), japaneseForTests = reader)

    @Test fun googleUsesMainDetectionAndOriginalCharacters() = runBlocking {
        val lines = listOf(LyricLine(0, "强い心を守る"), LyricLine(2_000, "月光"))
        val result = romanizer(fakeGoogle).romanize("ja-google", lines, emptySet(), network = true)
        assertEquals(listOf("auto" to listOf("强い心を守る"), "auto" to listOf("月光")), asked)
        assertEquals(listOf("roman", "roman"), result.map { it!!.text })
    }

    @Test fun existingChoicesArePreservedButDoNotChangeMainLyricsBehavior() = runBlocking {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("roman-main-test", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("ja-read:has-kana", false).putBoolean("ja-read:han-only", true)
            .putBoolean("normal-override", true).commit()
        val r = Romanizer(fakeGoogle, dao, ModelStore(RuntimeEnvironment.getApplication(), fakeGoogle),
            CoroutineScope(Dispatchers.Default), songPrefs = prefs)
        assertEquals(false, r.japaneseChoice("has-kana"))
        assertEquals(true, r.japaneseChoice("han-only"))
        assertEquals(mapOf("normal-override" to true), r.overrides.value)
        val kana = r.romanize("has-kana", listOf(LyricLine(0, "強い")), emptySet(), network = false)
        assertEquals(null, kana[0]) // main does not fall back to pinyin for lyrics containing kana
        r.romanize("han-only", listOf(LyricLine(0, "月光")), emptySet(), network = true)
        assertEquals("auto" to listOf("月光"), asked.single())
        assertEquals(false, prefs.getBoolean("ja-read:has-kana", true))
        assertEquals(true, prefs.getBoolean("ja-read:han-only", false))
    }

    @Test fun aChineseSongStaysChinese() = runBlocking {
        romanizer(fakeGoogle).romanize("zh", listOf(LyricLine(0, "我爱你"), LyricLine(2_000, "月光")), emptySet(), network = true)
        assertEquals("auto", asked.single().first)
        assertEquals(listOf("我爱你", "月光"), asked.single().second)
    }

    @Test fun dictionaryUsesOriginalLyricsAndKeepsTimings() = runBlocking {
        val reader = Japanese.bundled()
        val line = LyricLine(0, "强い心", 2_000, words = listOf(
            LyricWord(0, 1_000, "强い"), LyricWord(1_000, 2_000, "心")))
        val result = romanizer(fakeGoogle, reader).romanize("ja-dict", listOf(line), emptySet(), network = false)[0]!!
        assertEquals(reader.romanize(line.text).trim(), result.text)
        assertEquals(listOf(0L, 1_000L), result.words.map { it.startMs })
        assertEquals(listOf(1_000L, 2_000L), result.words.map { it.endMs })
        assertTrue(asked.isEmpty())
    }

    @Test fun titleSearchSpellsTheOriginalText() = runBlocking {
        val reader = Japanese.bundled()
        val spelling = romanizer(fakeGoogle, reader).spellTitle("强い")
        assertEquals(reader.romanize("强い").trim(), spelling)
        assertTrue(spelling != reader.romanize("強い").trim())
        assertTrue(asked.isEmpty())
    }

    @Test fun oldVersionFourCachedLyricsAreNotReused() = runBlocking {
        val line = LyricLine(0, "かな")
        val hash = (line.text + "|").hashCode()
        val oldKey = "ja-cache|4|-|j||$hash"
        saved[oldKey] = RomanizedEntity(oldKey, """[{"t":"stale"}]""", 0L)
        val result = romanizer(fakeGoogle).romanize("ja-cache", listOf(line), emptySet(), network = false)
        assertEquals("kana", result.single()!!.text)
        assertTrue(saved.containsKey(oldKey))
        assertTrue(saved.keys.any { it.startsWith("ja-cache|5|") })
    }

    @Test fun theTextKeepsItsLength() {
        assertEquals("強い", JapaneseKanji.fromChinese("强い"))
        assertEquals("机の上", JapaneseKanji.fromChinese("机の上"))
        assertEquals("nothing to do", JapaneseKanji.fromChinese("nothing to do"))
    }

    /** Only forms Japanese doesn't use: a kanji the dictionary can read must never be changed into another one. */
    @Test fun everyPairIsRight() {
        val ja = Japanese.bundled()
        val known = JapaneseKanji.pairs.keys.filter { Script.foreignIn(ja.romanize(it.toString())).isEmpty() }
        assertTrue("Japanese reads these itself, so they can't be in the table: $known", known.isEmpty())
        val unread = JapaneseKanji.pairs.values.toSet().filter { Script.foreignIn(ja.romanize(it.toString())).isNotEmpty() }
        println("[kanji] ${JapaneseKanji.pairs.size} pairs; no reading on their own: $unread")
        assertFalse("nothing maps onto another Chinese form", JapaneseKanji.pairs.values.any { it in JapaneseKanji.pairs })
    }

}
