package com.vinz.music.data

import com.vinz.music.R
import com.vinz.music.tr
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Big data files some features need, downloaded the first time they're used rather than carried in every APK:
 * each one a zip attached to Vinz's own GitHub release `models-v1`, checked against its SHA-256 before use, and kept
 * in `filesDir/models` (left out of backups; it can always be downloaded again).
 */
class ModelStore(private val context: Context, private val http: OkHttpClient) {
    enum class Model(val id: String, val file: String, val sha256: String, val bytes: Long, val title: String, /** Its size unpacked on the phone. */ val unpacked: Long) {
        /** Kuromoji's IPADIC dictionary (Apache-2.0 / IPADIC licence): readings for Japanese lyrics' kanji. */
        JAPANESE(
            "ja-ipadic", "ja-ipadic-0.9.0.zip",
            "286c372433219548c20fed47f813a84b334a68a7eb694787369789d8008f0b2a", 13_331_021, tr(R.string.japanese_kanji_readings), 33_443_143,
        ),
        /** Deezer's Spleeter, 2 stems, as sherpa-onnx exported it (fp16): Sing's vocal separation. */
        SING_MODEL(
            "sing-spleeter", "sing-spleeter-2stems-fp16.zip",
            "69714083b6fd5a88aa93cc021b4fb9534388960a532f6c987036e30f5332c191", 36370454, tr(R.string.sing_s_voice_separation), 39374474,
        ),
        /** ONNX Runtime's native libraries (Microsoft, MIT), which Sing runs Spleeter on, one per kind of processor. */
        SING_RUNTIME_ARM64(
            "onnxruntime-arm64-v8a", "onnxruntime-1.31.0-arm64-v8a.zip",
            "5a6c18c7958797caafa1cd7e6f30334e99cf5b6022cdc5ca1f047c9d94552d7a", 12864089, tr(R.string.sing_s_engine), 34209753,
        ),
        SING_RUNTIME_ARMV7(
            "onnxruntime-armeabi-v7a", "onnxruntime-1.31.0-armeabi-v7a.zip",
            "9d6ca9234650753b0b7137af5db4ef35520cda6796fd49b2d13b58cfaacc1eaf", 11623545, tr(R.string.sing_s_engine), 24183085,
        ),
        SING_RUNTIME_X86_64(
            "onnxruntime-x86_64", "onnxruntime-1.31.0-x86_64.zip",
            "07fee2427e8939edd05d0371ce4c15adc57917fbcf39dc07ce5afe76426aec95", 14935078, tr(R.string.sing_s_engine), 40829017,
        ),
        ;

        companion object {
            /** "49 MB download, 74 MB on the phone": the same words wherever the size of [models] is told. */
            fun sizeText(vararg models: Model): String =
                tr(R.string.mb_download_mb_on_the_phone, models.sumOf { it.bytes } / 1_000_000, models.sumOf { it.unpacked } / 1_000_000)

            /** The engine build for this phone's processor (null on one Sing can't run on). */
            fun singRuntime(): Model? {
                val abis = if (android.os.Process.is64Bit()) android.os.Build.SUPPORTED_64_BIT_ABIS else android.os.Build.SUPPORTED_32_BIT_ABIS
                return when (abis.firstOrNull()) {
                    "arm64-v8a" -> SING_RUNTIME_ARM64
                    "armeabi-v7a" -> SING_RUNTIME_ARMV7
                    "x86_64" -> SING_RUNTIME_X86_64
                    else -> null
                }
            }
        }
    }

    sealed interface State {
        data object Missing : State
        data class Downloading(val fraction: Float) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val root by lazy { File(context.filesDir, "models") }
    private val _states = MutableStateFlow(Model.entries.associateWith { if (ready(it)) State.Ready else State.Missing })
    val states: StateFlow<Map<Model, State>> = _states
    private val locks = Model.entries.associateWith { Mutex() }

    /** Looks at what's on the phone again (a model removed or put back outside a download). */
    fun refresh() = _states.update { states ->
        states.mapValues { (m, s) -> if (s is State.Downloading) s else if (ready(m)) State.Ready else if (s is State.Failed) s else State.Missing }
    }

    fun dir(m: Model) = File(root, m.id)

    /** A marker alone is not enough: a deleted or zero-byte model file must make the feature download again. */
    fun ready(m: Model): Boolean {
        val dir = dir(m)
        val marker = File(dir, READY)
        return runCatching {
            val required = requiredFiles(m)
            marker.isFile && marker.readText() == m.sha256 && required.isNotEmpty() && required.all {
                File(dir, it).let { file -> file.isFile && file.length() > 0L }
            }
        }.getOrDefault(false)
    }

    /**
     * Downloads, checks and unpacks [m] (once at a time; at once if it's already there). True when it's ready.
     * The download runs on the store's own scope: closing the sheet that started it (and so cancelling the caller)
     * leaves it going, and its state ends up Ready, not "failed", when it finishes (2026-10-20: Sing's engine had
     * downloaded, but Settings still offered to download it).
     */
    suspend fun download(m: Model): Boolean = work.async {
        locks.getValue(m).withLock {
            if (ready(m)) return@withLock true.also { set(m, State.Ready) }
            set(m, State.Downloading(0f))
            val result = runCatching { fetch(m) }
            set(m, if (ready(m)) State.Ready else State.Failed(result.exceptionOrNull()?.message ?: tr(R.string.download_failed)))
            ready(m)
        }
    }.await()

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Removes [m] from the phone (Settings, to free the space). */
    fun delete(m: Model) {
        dir(m).deleteRecursively()
        set(m, State.Missing)
    }

    fun sizeOnPhone(m: Model): Long = dir(m).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun set(m: Model, s: State) = _states.update { it + (m to s) }

    private fun fetch(m: Model) {
        root.mkdirs()
        val part = File(root, m.file + ".part")
        try {
            val req = Request.Builder().url("$BASE/${m.file}").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException(tr(R.string.the_download_answered_http, resp.code))
                val body = resp.body
                val total = body.contentLength().takeIf { it > 0 } ?: m.bytes
                val digest = MessageDigest.getInstance("SHA-256")
                part.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var shown = 0f
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (m != Model.JAPANESE && done > m.bytes) throw IOException(tr(R.string.the_download_didn_t_check_out))
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            val f = (done.toFloat() / total).coerceIn(0f, 1f)
                            if (f - shown >= 0.01f) { shown = f; set(m, State.Downloading(f)) }
                        }
                    }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (sha != m.sha256) throw IOException(tr(R.string.the_download_didn_t_check_out))
            }
            install(m, part)
        } finally {
            // A stopped request, a failed checksum and a failed unpack all leave the next attempt clean.
            part.delete()
        }
    }

    /** Unpacks a checked zip into the model's folder; the ready marker goes last, so a half-unpacked one never counts. */
    internal fun install(m: Model, zip: File) {
        val dir = dir(m)
        try {
            if (!zip.isFile || zip.length() == 0L) throw IOException(tr(R.string.download_failed))
            dir.deleteRecursively()
            if (!dir.mkdirs()) throw IOException(tr(R.string.download_failed))
            val strictNames = mutableSetOf<String>()
            var expanded = 0L
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    // Old Kuromoji archives have used resource folders. Keep their original basename flattening;
                    // new Sing packages are deliberately flat, so reject paths and duplicate output names there.
                    val name = if (m == Model.JAPANESE) File(e.name).name else e.name
                    if (name.isBlank() || (m != Model.JAPANESE && (!safeEntryName(name) || !strictNames.add(name)))) {
                        throw IOException(tr(R.string.download_failed))
                    }
                    File(dir, name).outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = z.read(buffer)
                            if (count < 0) break
                            expanded += count
                            if (m != Model.JAPANESE && expanded > maxExpanded(m)) throw IOException(tr(R.string.download_failed))
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            val required = requiredFiles(m)
            if (required.isEmpty() || !required.all { File(dir, it).let { file -> file.isFile && file.length() > 0L } }) {
                throw IOException(tr(R.string.download_failed))
            }
            // Native code loaded from here is kept read-only (Android 14 asks that of anything loaded at run time).
            dir.listFiles()?.filter { it.name.endsWith(".so") }?.forEach { it.setWritable(false, false) }
            File(dir, READY).writeText(m.sha256)
        } catch (failure: Throwable) {
            dir.deleteRecursively()
            throw failure
        } finally {
            zip.delete()
        }
    }

    private fun requiredFiles(m: Model): List<String> = when (m) {
        Model.SING_MODEL -> listOf("vocals.fp16.onnx", "accompaniment.fp16.onnx")
        Model.SING_RUNTIME_ARM64, Model.SING_RUNTIME_ARMV7, Model.SING_RUNTIME_X86_64 -> listOf("libonnxruntime.so")
        // Kuromoji has several versioned dictionary resources; a checked package must at least contain data.
        Model.JAPANESE -> dir(m).listFiles()?.filter { it.name != READY }?.map { it.name } ?: emptyList()
    }

    private fun safeEntryName(name: String): Boolean =
        name.isNotBlank() && name == File(name).name && !name.startsWith('.') && !name.contains('\\')

    private fun maxExpanded(m: Model): Long = maxOf(m.unpacked * 2, MIN_EXPANDED_BYTES)

    private companion object {
        const val BASE = "https://github.com/nastechtest/Vinz/releases/download/models-v1"
        const val READY = ".ready"
        const val MIN_EXPANDED_BYTES = 8L * 1024 * 1024
    }
}
