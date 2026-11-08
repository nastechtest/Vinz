package com.vinz.music

import com.vinz.music.data.ModelStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Regression coverage for checked, retryable Sing model installs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ModelStoreTest {
    private val model = ModelStore.Model.SING_MODEL
    private lateinit var store: ModelStore

    @Before fun setUp() {
        store = ModelStore(RuntimeEnvironment.getApplication(), OkHttpClient())
        store.delete(model)
    }

    @After fun tearDown() {
        store.delete(model)
    }

    @Test fun incompleteOrCorruptArchiveLeavesNoModelOrPart() {
        val incomplete = zip("vocals.fp16.onnx" to "model")
        assertThrows(IOException::class.java) { store.install(model, incomplete) }
        assertFalse(store.dir(model).exists())
        assertFalse(incomplete.exists())

        val corruptPart = temp("corrupt.part").apply { writeText("not a zip") }
        assertThrows(IOException::class.java) { store.install(model, corruptPart) }
        assertFalse(store.dir(model).exists())
        assertFalse(corruptPart.exists())
    }

    @Test fun validArchiveWritesCheckedMarkerAndReadinessNeedsEveryFile() {
        store.install(model, validZip())
        val dir = store.dir(model)
        assertEquals(model.sha256, File(dir, ".ready").readText())
        assertTrue(store.ready(model))

        val accompaniment = File(dir, "accompaniment.fp16.onnx")
        accompaniment.writeBytes(ByteArray(0))
        assertFalse(store.ready(model))
        accompaniment.delete()
        accompaniment.mkdirs()
        File(accompaniment, "not-the-model").writeText("data")
        assertFalse(store.ready(model))
        accompaniment.deleteRecursively()
        accompaniment.writeText("backing")
        File(dir, ".ready").writeText("wrong checksum")
        assertFalse(store.ready(model))
    }

    @Test fun failedInstallCanRetryWithACompleteArchive() {
        assertThrows(IOException::class.java) { store.install(model, zip("vocals.fp16.onnx" to "model")) }
        store.install(model, validZip())
        assertTrue(store.ready(model))
    }

    @Test fun zeroLengthAndDuplicateNameArchivesAreRejected() {
        val empty = temp("empty.zip")
        assertThrows(IOException::class.java) { store.install(model, empty) }
        assertFalse(store.dir(model).exists())
        assertFalse(empty.exists())

        val duplicate = duplicateZip(
            "vocals.fp16.onnx" to "first",
            "vocals.fp16.onnx" to "second",
            "accompaniment.fp16.onnx" to "backing",
        )
        assertThrows(IOException::class.java) { store.install(model, duplicate) }
        assertFalse(store.dir(model).exists())
        assertFalse(duplicate.exists())
    }

    @Test fun pathEntriesAreRejectedRatherThanFlattenedIntoTheModel() {
        val unsafe = zip("../vocals.fp16.onnx" to "model", "accompaniment.fp16.onnx" to "backing")
        assertThrows(IOException::class.java) { store.install(model, unsafe) }
        assertFalse(store.dir(model).exists())
        assertFalse(unsafe.exists())
    }

    @Test fun oversizedExpansionLeavesNoInstalledRuntime() {
        val runtime = ModelStore.Model.SING_RUNTIME_ARMV7
        val archive = temp("oversized.zip")
        val chunk = ByteArray(1024 * 1024)
        ZipOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("libonnxruntime.so"))
            repeat((runtime.unpacked * 2 / chunk.size).toInt() + 1) { output.write(chunk) }
            output.closeEntry()
        }
        try {
            assertThrows(IOException::class.java) { store.install(runtime, archive) }
            assertFalse(store.dir(runtime).exists())
            assertFalse(archive.exists())
            assertFalse(store.ready(runtime))
        } finally {
            store.delete(runtime)
            archive.delete()
        }
    }

    @Test fun japaneseKeepsLegacyNestedArchiveFlattening() {
        val japanese = ModelStore.Model.JAPANESE
        val legacy = zip("dictionary/token.bin" to "data")
        try {
            store.install(japanese, legacy)
            assertTrue(File(store.dir(japanese), "token.bin").isFile)
            assertTrue(store.ready(japanese))
        } finally {
            store.delete(japanese)
        }
    }

    @Test fun downloadFailuresAreRetryableAndAlwaysCleanPartFiles() = runBlocking {
        val outcomes = ArrayDeque<(Request) -> Response>().apply {
            add { request -> response(request, 404, "missing".toResponseBody()) }
            add { request -> response(request, 200, "wrong checksum".toResponseBody()) }
            add { request -> response(request, 200, interruptedBody()) }
        }
        var calls = 0
        store = ModelStore(RuntimeEnvironment.getApplication(), OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            calls++
            outcomes.removeFirst()(chain.request())
        }).build())
        repeat(3) {
            assertFalse(store.download(model))
            assertTrue(store.states.value.getValue(model) is ModelStore.State.Failed)
            assertFalse(File(store.dir(model).parentFile, model.file + ".part").exists())
            assertFalse(store.dir(model).exists())
        }
        assertEquals(3, calls)
    }

    private fun validZip() = zip("vocals.fp16.onnx" to "model", "accompaniment.fp16.onnx" to "backing")

    private fun zip(vararg entries: Pair<String, String>): File = temp("model.zip").also { file ->
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, data) ->
                output.putNextEntry(ZipEntry(name))
                output.write(data.toByteArray())
                output.closeEntry()
            }
        }
    }

    /** ZipOutputStream blocks duplicate entry names, so write local records directly for this regression. */
    private fun duplicateZip(vararg entries: Pair<String, String>) = temp("duplicate.zip").also { file ->
        file.outputStream().buffered().use { out ->
            entries.forEach { (name, text) ->
                val nameBytes = name.toByteArray()
                val data = text.toByteArray()
                val crc = java.util.zip.CRC32().apply { update(data) }.value
                fun number(value: Long, bytes: Int) = repeat(bytes) { out.write((value shr (it * 8)).toInt()) }
                number(0x04034b50, 4); number(20, 2); number(0, 2); number(0, 2)
                number(0, 2); number(0, 2); number(crc, 4); number(data.size.toLong(), 4); number(data.size.toLong(), 4)
                number(nameBytes.size.toLong(), 2); number(0, 2)
                out.write(nameBytes); out.write(data)
            }
        }
    }

    private fun response(request: Request, code: Int, body: ResponseBody) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body).build()

    private fun interruptedBody() = object : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength() = 16L
        override fun source() = object : ForwardingSource(Buffer().writeUtf8("partial")) {
            private var interrupted = false
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (interrupted) throw IOException("interrupted response")
                interrupted = true
                return super.read(sink, byteCount)
            }
        }.buffer()
    }

    private fun temp(suffix: String) = File(RuntimeEnvironment.getApplication().cacheDir, "model-store-${UUID.randomUUID()}-$suffix")
}
