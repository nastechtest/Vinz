package com.vinz.music.connect

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.Socket
import java.net.URLClassLoader
import java.nio.file.Files
import java.security.AlgorithmParameters
import java.security.Provider
import java.security.Security
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/** Google Cast without Google's SDK: the wire format, devices from mDNS, and a whole session with a pretend device. */
class GoogleCastTest {
    private val url = "http://192.168.1.5:47322/cast/tok/X0tQ404rwUk.wav"
    private val media = CastMedia("Linger (SiriusXM Session)", "Royel Otis", "Linger / Heading For The Door", "http://192.168.1.5:47322/cast/tok/art.jpg", "audio/wav", 186_000)

    private fun link(fake: FakeCastDevice) = GoogleCastLink(fake.renderer()) { h, p -> Socket(h, p) }

    @Test
    fun messagesRoundTrip() {
        val m = CastMessage("sender-vinz", "receiver-0", CastNs.RECEIVER, """{"type":"GET_STATUS","requestId":1}""")
        val bytes = m.encode()
        // protocol_version 0, then source_id as field 2 (length-delimited), as Cast devices expect.
        assertEquals(0x08, bytes[0].toInt()); assertEquals(0x00, bytes[1].toInt()); assertEquals(0x12, bytes[2].toInt())
        assertEquals(m, CastMessage.decode(bytes))
        // Non-ASCII survives (song titles go in these).
        val accents = m.copy(payload = """{"title":"Señorita — 사랑해"}""")
        assertEquals(accents, CastMessage.decode(accents.encode()))
    }

    @Test
    fun framing() {
        val out = ByteArrayOutputStream()
        val a = CastMessage("a", "b", CastNs.HEARTBEAT, """{"type":"PING"}""")
        val b = CastMessage("b", "a", CastNs.HEARTBEAT, """{"type":"PONG"}""")
        CastWire.write(out, a); CastWire.write(out, b)
        val bytes = out.toByteArray()
        assertEquals(a.encode().size, java.nio.ByteBuffer.wrap(bytes, 0, 4).int)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        assertEquals(a, CastWire.read(input))
        assertEquals(b, CastWire.read(input))
        // A binary payload (device authentication) isn't something Vinz reads: skipped, not a failure.
        val binary = byteArrayOf(0x08, 0x00, 0x12, 0x01, 0x61, 0x1a, 0x01, 0x62, 0x22, 0x01, 0x63, 0x28, 0x01, 0x3a, 0x02, 0x01, 0x02)
        assertNull(CastMessage.decode(binary))
        // Nonsense lengths end the connection rather than allocating gigabytes.
        val huge = DataInputStream(ByteArrayInputStream(byteArrayOf(0x7f, 0x00, 0x00, 0x00)))
        assertTrue(runCatching { CastWire.read(huge) }.isFailure)
    }

    @Test
    fun devicesFromTheirAnnouncement() {
        val r = GoogleCastDevices.renderer(
            "Google-TV-Streamer-1a2b3c", "192.168.1.20", 8009,
            mapOf("fn" to "Living Room TV", "md" to "Google TV Streamer", "id" to "1a2b3c4d", "rs" to ""),
        )!!
        assertEquals("cast:1a2b3c4d", r.id)
        assertEquals("Living Room TV", r.name)
        assertEquals("Google TV Streamer · Google Cast", r.detail)
        assertEquals(RendererKind.GOOGLE_CAST, r.kind)
        assertEquals("192.168.1.20", r.host)
        assertEquals(8009, r.port)
        // A speaker group listens on its own port.
        assertEquals(32187, GoogleCastDevices.renderer("Group", "192.168.1.21", 32187, mapOf("fn" to "Downstairs", "md" to "Google Cast Group"))!!.port)
        assertNull(GoogleCastDevices.renderer("x", "", 8009, emptyMap()))
    }

    @Test
    fun loadsPlaysPausesSeeksAndFollows() = runBlocking {
        FakeCastDevice(fetch = false).use { fake ->
            val link = link(fake)
            try {
                // Connecting opens the Default Media Receiver on the TV.
                assertEquals("NO_MEDIA_PRESENT", link.transport().state)
                assertTrue("LAUNCH" in fake.types(CastNs.RECEIVER))
                assertTrue(fake.appRunning)

                link.load(url, "<DIDL-Lite/>", media)
                val load = fake.received.last { it.second["type"]?.jsonPrimitive?.content == "LOAD" }.second
                val m = load["media"]!!.jsonObject
                assertEquals(url, m["contentId"]!!.jsonPrimitive.content)
                assertEquals("audio/wav", m["contentType"]!!.jsonPrimitive.content)
                assertEquals("BUFFERED", m["streamType"]!!.jsonPrimitive.content)
                assertEquals(186.0, m["duration"]!!.jsonPrimitive.content.toDouble(), 0.001)
                val meta = m["metadata"]!!.jsonObject
                assertEquals("Linger (SiriusXM Session)", meta["title"]!!.jsonPrimitive.content)
                assertEquals("Royel Otis", meta["artist"]!!.jsonPrimitive.content)
                assertEquals("false", load["autoplay"]!!.jsonPrimitive.content)
                assertTrue(link.transport().paused)

                link.play()
                delay(1_200)
                assertTrue(link.transport().playing)
                val p = link.position()
                assertEquals(url, p.trackUri)
                assertTrue("playhead runs: ${p.relMs}", p.relMs!! in 900..3_000)
                assertEquals(186_000L, p.durationMs)

                link.seek(60_000)
                link.transport()
                assertTrue("after the seek: ${link.position().relMs}", link.position().relMs!! in 60_000..62_000)

                link.pause()
                assertTrue(link.transport().paused)

                link.setVolume(30)
                assertEquals(30, link.volume())

                fake.finish()
                delay(300)
                assertTrue(link.transport().stopped)
                // The song it had is still the one reported, so the phone knows which song ended.
                assertEquals(url, link.position().trackUri)
            } finally {
                link.close()
            }
            // Closing takes Vinz's player off the TV.
            assertTrue("STOP" in fake.types(CastNs.RECEIVER))
            assertFalse(fake.appRunning)
        }
    }

    /** The way the app connects: TLS to a device with a self-signed certificate. */
    @Test
    fun overTlsLikeARealDevice() = runInIsolatedJvm(TLS_PROBE_ARG, TLS_PROBE_SUCCESS)

    @Test
    fun tlsStillWorksAfterRobolectricChangesProviders() = runInIsolatedJvm(CONTAMINATED_PARENT_ARG, CONTAMINATED_PARENT_SUCCESS)

    private fun runInIsolatedJvm(argument: String, success: String) {
        // Robolectric installs Conscrypt globally. SunJSSE can then select it for EC parameters and fail during
        // static initialization, even when its SSLContext is pinned. A fresh JVM keeps this real TLS test independent
        // of test order without changing providers underneath other tests (or changing production's TLS provider).
        val tempDir = Files.createTempDirectory("vinz cast tls ").toFile()
        val classpathJar = File(tempDir, "classpath.jar")
        val output = File(tempDir, "output.log")
        try {
            // Gradle's test classpath can exceed Windows' command-line limit. A manifest classpath also supports
            // paths with spaces; include URL classloaders for IDEs that do not put tests on java.class.path.
            val entries = linkedSetOf<String>()
            System.getProperty("java.class.path").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }
                .forEach { entries += File(it).absoluteFile.toURI().toASCIIString() }
            listOf(javaClass.classLoader, Thread.currentThread().contextClassLoader).forEach { first ->
                generateSequence(first) { it.parent }.filterIsInstance<URLClassLoader>().forEach { loader ->
                    loader.urLs.filter { it.protocol == "file" }.forEach { entries += it.toURI().toASCIIString() }
                }
            }
            val manifest = Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[Attributes.Name.CLASS_PATH] = entries.joinToString(" ")
            }
            JarOutputStream(classpathJar.outputStream(), manifest).use { }
            val java = File(System.getProperty("java.home"), "bin/java" + if (File.separatorChar == '\\') ".exe" else "")
            val process = ProcessBuilder(java.path, "-Djava.io.tmpdir=${tempDir.absolutePath}", "-cp", classpathJar.path, javaClass.name, argument)
                .redirectErrorStream(true).redirectOutput(output).start()
            try {
                // The contaminated parent itself starts a child with a 60-second timeout; allow time for its cleanup.
                val completed = process.waitFor(if (argument == CONTAMINATED_PARENT_ARG) 90 else 60, TimeUnit.SECONDS)
                if (!completed) stopProcessTree(process)
                val result = output.readText()
                assertTrue("Cast TLS child JVM timed out:\n$result", completed)
                assertEquals("Cast TLS child JVM failed:\n$result", 0, process.exitValue())
                if (argument == CONTAMINATED_PARENT_ARG && CONSCRYPT_UNAVAILABLE in result) {
                    assumeTrue("Conscrypt has no native library for this platform", false)
                }
                assertTrue("Cast TLS child JVM did not complete its session:\n$result", success in result)
            } finally {
                if (process.isAlive) stopProcessTree(process)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun stopProcessTree(process: Process) {
        // Android's compile stubs omit ProcessHandle, although the JDK 17 running these tests provides it.
        runCatching {
            val type = Class.forName("java.lang.ProcessHandle")
            val handle = Process::class.java.getMethod("toHandle").invoke(process)
            val children = type.getMethod("descendants").invoke(handle) as java.util.stream.Stream<*>
            children.use { it.forEach { child -> type.getMethod("destroyForcibly").invoke(child) } }
        }
        process.destroyForcibly()
        runCatching { process.waitFor(5, TimeUnit.SECONDS) }
    }

    private fun exerciseTlsSession() = runBlocking {
        FakeCastDevice(fetch = false, tls = true).use { fake ->
            val link = GoogleCastLink(fake.renderer())
            try {
                assertEquals("NO_MEDIA_PRESENT", link.transport().state)
                link.load(url, "", media)
                link.play()
                assertTrue(link.transport().playing)
                assertTrue("CONNECT" in fake.types(CastNs.CONNECTION))
                assertTrue("LAUNCH" in fake.types(CastNs.RECEIVER))
                assertTrue("LOAD" in fake.types(CastNs.MEDIA))
                assertTrue("PLAY" in fake.types(CastNs.MEDIA))
            } finally {
                link.close()
            }
        }
    }

    @Test
    fun anotherAppTakingOverEndsCasting() = runBlocking {
        FakeCastDevice(fetch = false).use { fake ->
            val link = link(fake)
            try {
                link.transport()
                link.load(url, "", media)
                link.play()
                fake.takeOver()
                delay(300)
                assertEquals(GoogleCastLink.OTHER, link.position().trackUri)
                assertTrue(link.transport().stopped)
                // Vinz doesn't take the TV back: its commands fail instead.
                assertTrue(runCatching { link.play() }.isFailure)
                val launches = fake.types(CastNs.RECEIVER).count { it == "LAUNCH" }
                assertEquals(1, launches)
            } finally {
                link.close()
            }
            // It doesn't close the other app either.
            assertFalse("STOP" in fake.types(CastNs.RECEIVER))
        }
    }

    @Test
    fun aRefusedSongIsReportedLikeARendererRefusingIt() = runBlocking {
        FakeCastDevice(fetch = false, failLoads = true).use { fake ->
            val link = link(fake)
            try {
                link.transport()
                val e = runCatching { link.load(url, "", media) }.exceptionOrNull()
                assertTrue("refused: $e", e is UpnpException && e.code == 714)
            } finally {
                link.close()
            }
        }
    }

    @Test
    fun theDeviceFetchesWhatItIsSent() = runBlocking {
        // A tiny web server with a WAV head, like the phone's media server.
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/cast/tok/") { ex ->
            val head = ByteArray(44).also { "RIFF".toByteArray().copyInto(it) }
            ex.responseHeaders.add("Content-Type", "audio/wav")
            ex.sendResponseHeaders(206, head.size.toLong())
            ex.responseBody.use { it.write(head) }
        }
        server.start()
        try {
            FakeCastDevice(fetch = true).use { fake ->
                val link = link(fake)
                try {
                    link.transport()
                    val local = "http://127.0.0.1:${server.address.port}/cast/tok/song.wav"
                    link.load(local, "", media)
                    assertEquals(listOf(local), fake.fetched.toList())
                } finally {
                    link.close()
                }
            }
        } finally {
            server.stop(0)
        }
    }

    /**
     * Not a test of its own: a Cast device on this PC for the phone (or emulator) to cast to, for VINZ_FAKE_CAST
     * minutes, playing what it's sent out loud. On the phone: `adb forward tcp:47322 tcp:47322` (so it can fetch from
     * the phone's media server) and a line "googlecast://<this PC's address>:8009" in Vinz Dev's
     * files/connect_renderer.txt (from the emulator, 10.0.2.2).
     */
    @Test
    fun castFromThePhone() {
        val minutes = System.getenv("VINZ_FAKE_CAST")?.toIntOrNull()
        assumeTrue(minutes != null)
        // VINZ_FAKE_CAST_AUDIBLE=1 plays it on this PC's speakers; otherwise it's only read, at playing speed.
        val audible = System.getenv("VINZ_FAKE_CAST_AUDIBLE") == "1"
        FakeCastDevice(bind = "0.0.0.0", port = 8009, audible = audible, rewriteHost = "127.0.0.1:47322", tls = true, log = { println("[cast] $it") }).use {
            Thread.sleep(minutes!! * 60_000L)
        }
    }

    companion object {
        private const val TLS_PROBE_ARG = "--tls-probe"
        private const val TLS_PROBE_SUCCESS = "Cast TLS session passed"
        private const val CONTAMINATED_PARENT_ARG = "--tls-contaminated-parent"
        private const val CONTAMINATED_PARENT_SUCCESS = "Cast TLS survived Conscrypt in parent"
        private const val CONSCRYPT_UNAVAILABLE = "Conscrypt native library unavailable"

        /** Entry points for the isolated TLS session and its provider-order regression test. */
        @JvmStatic
        fun main(args: Array<String>) {
            when (args.single()) {
                TLS_PROBE_ARG -> {
                    GoogleCastTest().exerciseTlsSession()
                    println(TLS_PROBE_SUCCESS)
                }
                CONTAMINATED_PARENT_ARG -> {
                    // Only this disposable parent is changed; the Gradle test worker and its other tests are untouched.
                    val conscrypt = Class.forName("org.conscrypt.Conscrypt")
                    if (conscrypt.getMethod("isAvailable").invoke(null) != true) {
                        println(CONSCRYPT_UNAVAILABLE)
                        return
                    }
                    val provider = Class.forName("org.conscrypt.OpenSSLProvider").getConstructor().newInstance() as Provider
                    check(Security.insertProviderAt(provider, 1) == 1)
                    check(AlgorithmParameters.getInstance("EC").provider.name == "Conscrypt")
                    GoogleCastTest().overTlsLikeARealDevice()
                    println(CONTAMINATED_PARENT_SUCCESS)
                }
                else -> error("Unknown Cast TLS probe")
            }
        }
    }
}
