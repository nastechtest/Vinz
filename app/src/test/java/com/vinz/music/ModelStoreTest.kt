package com.vinz.music

import android.app.Application
import android.content.ContextWrapper
import com.vinz.music.data.ModelStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic archives exercise install directly; production calls it only after checking the archive hash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ModelStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val model = ModelStore.Model.SING_MODEL
    private val runtime = ModelStore.Model.SING_RUNTIME_ARM64
    private val modelEntries = listOf("vocals.fp16.onnx" to "v", "accompaniment.fp16.onnx" to "a")

    private fun store(client: OkHttpClient = OkHttpClient()): ModelStore {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = temp.root
        }
        return ModelStore(context, client)
    }

    private fun archive(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("model", ".zip", temp.root)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, data) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(data.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test fun readyRequiresMatchingMarkerAndAllNonEmptyModelFiles() {
        val store = store()
        val zip = archive(*modelEntries.toTypedArray())
        store.install(model, zip)
        assertFalse(zip.exists())
        assertTrue(store.ready(model))
        val dir = store.dir(model)
        File(dir, ".ready").writeText("wrong")
        assertFalse(store.ready(model))
        File(dir, ".ready").writeText(model.sha256)
        File(dir, "vocals.fp16.onnx").writeText("")
        assertFalse(store.ready(model))
        File(dir, "vocals.fp16.onnx").writeText("v")
        assertTrue(store.ready(model))
        File(dir, "accompaniment.fp16.onnx").delete()
        store.refresh()
        assertFalse(store.ready(model))
        assertEquals(ModelStore.State.Missing, store.states.value[model])
    }

    @Test fun runtimeRequiresItsLibraryAndMarker() {
        val store = store()
        store.install(runtime, archive("libonnxruntime.so" to "binary", "LICENSE.txt" to "license"))
        assertTrue(store.ready(runtime))
        File(store.dir(runtime), "libonnxruntime.so").delete()
        assertFalse(store.ready(runtime))
    }

    @Test fun missingOrEmptyRequiredFilesFailAndCleanEverything() {
        val store = store()
        val cases = listOf(arrayOf("vocals.fp16.onnx" to "v"), arrayOf("vocals.fp16.onnx" to "v", "accompaniment.fp16.onnx" to ""), emptyArray())
        for (entries in cases) {
            val zip = archive(*entries)
            assertThrows(IOException::class.java) { store.install(model, zip) }
            assertFalse(zip.exists())
            assertFalse(store.dir(model).exists())
            assertFalse(store.ready(model))
        }
    }

    @Test fun expansionLimitRejectsAndCleansPartialInstall() {
        val store = store()
        val zip = archive(*modelEntries.toTypedArray())
        assertThrows(IOException::class.java) { store.install(model, zip, expandedLimit = 1) }
        assertFalse(zip.exists())
        assertFalse(store.dir(model).exists())
        assertFalse(store.ready(model))
    }

    @Test fun unsafeSingNamesFailWithoutEscaping() {
        val store = store()
        for (name in listOf("../escape", "/absolute", "nested/file", "nested\\file", ".ready")) {
            val zip = archive(*(modelEntries + (name to "bad")).toTypedArray())
            assertThrows(IOException::class.java) { store.install(model, zip) }
            assertFalse(zip.exists())
            assertFalse(store.dir(model).exists())
            assertFalse(File(temp.root, "escape").exists())
        }
    }

    @Test fun duplicateOutputNameFailsAndCleansUp() {
        val store = store()
        val zip = archive(*(modelEntries + listOf("dupe1" to "a", "dupe2" to "b")).toTypedArray())
        // Replace both local and central-directory names, creating a duplicate ZIP entry.
        val bytes = zip.readBytes()
        val from = "dupe2".toByteArray()
        val to = "dupe1".toByteArray()
        for (i in 0..bytes.size - from.size) {
            if (from.indices.all { bytes[i + it] == from[it] }) to.indices.forEach { bytes[i + it] = to[it] }
        }
        zip.writeBytes(bytes)
        assertThrows(IOException::class.java) { store.install(model, zip) }
        assertFalse(zip.exists())
        assertFalse(store.dir(model).exists())
    }

    @Test fun japaneseStillAcceptsNestedDictionaryResources() {
        val store = store()
        val ja = ModelStore.Model.JAPANESE
        store.install(ja, archive("folder/dictionary.dat" to "data"))
        assertEquals("data", File(store.dir(ja), "dictionary.dat").readText())
        assertTrue(store.ready(ja))
        File(store.dir(ja), "dictionary.dat").delete()
        assertFalse(store.ready(ja))
    }

    @Test fun wrongChecksumAndHttpFailureRemovePartialDownload() = runBlocking {
        for (code in listOf(200, 503)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("test").body("untrusted".toResponseBody()).build()
            }.build()
            val store = store(client)
            assertFalse(store.download(model))
            assertTrue(store.states.value[model] is ModelStore.State.Failed)
            assertFalse(File(temp.root, "models/${model.file}.part").exists())
            assertFalse(store.ready(model))
        }
    }
}
