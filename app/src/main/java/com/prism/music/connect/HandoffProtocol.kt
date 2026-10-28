package com.prism.music.connect

import com.prism.music.data.model.Song
import com.prism.music.playback.together.fromPeer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What's playing, as it moves from one Prism to another: the whole queue, the song it's on and where, play order
 * (shuffled or not) and repeat. [order] lists queue indices in the order they play (empty when not shuffled).
 */
@Serializable
data class HandoffSnapshot(
    val songs: List<Song>,
    val index: Int,
    val positionMs: Long,
    val playing: Boolean,
    val shuffle: Boolean = false,
    val order: List<Int> = emptyList(),
    val repeat: Int = 0,
    val title: String = "",
) {
    /**
     * Made safe to play here: only YouTube songs ([fromPeer]; files on the other phone can't come along), with the
     * index and play order moved to match. Null when there's nothing left, or the song playing was one that can't come.
     */
    fun cleaned(): HandoffSnapshot? {
        val keep = songs.mapIndexedNotNull { i, s -> s.fromPeer()?.let { i to it } }.take(MAX_SONGS)
        val newIndex = HashMap<Int, Int>()
        keep.forEachIndexed { n, (old, _) -> newIndex[old] = n }
        val at = newIndex[index] ?: return null
        val playOrder = if (shuffle) order.mapNotNull { newIndex[it] }.distinct() else emptyList()
        // The order has to cover the whole queue exactly once; if it doesn't (an older Prism, a bad message), queue order.
        val orderOk = playOrder.size == keep.size
        return copy(
            songs = keep.map { it.second },
            index = at,
            positionMs = positionMs.coerceAtLeast(0),
            shuffle = shuffle && orderOk,
            order = if (shuffle && orderOk) playOrder else emptyList(),
            repeat = repeat.coerceIn(0, 2),
            title = title.take(200),
        )
    }

    /** How many songs the queue had that couldn't come (files on the other phone). */
    fun leftOut(cleaned: HandoffSnapshot): Int = songs.size - cleaned.songs.size

    companion object { const val MAX_SONGS = 2_000 }
}

/** One message per line between two Prisms moving playback ([Handoff]). */
@Serializable
sealed class HMsg {
    @Serializable @SerialName("hello")
    data class Hello(val name: String, val device: String, val version: Int = Handoff.VERSION) : HMsg()

    /** The receiving side's answer: who it is, and a fresh number for the caller to prove it's paired with. */
    @Serializable @SerialName("challenge")
    data class Challenge(val name: String, val device: String, val nonce: String) : HMsg()

    /** HMAC of the challenge with the pair's shared key. */
    @Serializable @SerialName("auth")
    data class Auth(val mac: String) : HMsg()

    @Serializable @SerialName("pair_request")
    data object PairRequest : HMsg()

    /** The receiving side is showing a code: type it on the caller. */
    @Serializable @SerialName("enter_code")
    data object EnterCode : HMsg()

    @Serializable @SerialName("pair")
    data class Pair(val code: String) : HMsg()

    /** The key the two now share (sent once, at pairing). */
    @Serializable @SerialName("paired")
    data class Paired(val secret: String) : HMsg()

    @Serializable @SerialName("ok")
    data object Ok : HMsg()

    @Serializable @SerialName("denied")
    data class Denied(val reason: String, val unpaired: Boolean = false) : HMsg()

    /** Play this here (the caller stops once it's going). */
    @Serializable @SerialName("move")
    data class Move(val snapshot: HandoffSnapshot) : HMsg()

    /** Send me what you're playing (and stop once I've got it). */
    @Serializable @SerialName("pull")
    data object Pull : HMsg()

    @Serializable @SerialName("snapshot")
    data class Snap(val snapshot: HandoffSnapshot) : HMsg()

    /** It's playing on this side now: the other side can stop. */
    @Serializable @SerialName("moved")
    data object Moved : HMsg()
}

object HWire {
    val json = Json { classDiscriminator = "t"; ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(m: HMsg): String = json.encodeToString(HMsg.serializer(), m)
    fun decode(line: String): HMsg? = runCatching { json.decodeFromString(HMsg.serializer(), line) }.getOrNull()
}

/** A connection between two Prisms: [send] and [read] one message per line. */
class HLink(private val socket: Socket) : Closeable {
    private val reader = com.prism.music.playback.together.LineReader(socket.getInputStream().buffered())
    private val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

    val remote: String get() = socket.inetAddress?.hostAddress ?: "?"

    fun timeout(ms: Int) { runCatching { socket.soTimeout = ms } }

    fun send(m: HMsg): Boolean = synchronized(writer) {
        runCatching { writer.write(HWire.encode(m)); writer.write("\n"); writer.flush() }.isSuccess
    }

    /** The next message it understands; null once the connection's gone (or the wait ran out). */
    fun read(): HMsg? {
        while (true) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: return null
            if (line.isBlank()) continue
            HWire.decode(line)?.let { return it }
        }
    }

    override fun close() { runCatching { socket.close() } }
}

object HandoffKeys {
    private val random = SecureRandom()

    fun newSecret(): String = hex(ByteArray(32).also { random.nextBytes(it) })
    fun newNonce(): String = hex(ByteArray(16).also { random.nextBytes(it) })
    fun newCode(): String = (100_000 + random.nextInt(900_000)).toString()

    /** Proof of the shared key for this challenge, tied to who's answering it. */
    fun mac(secretHex: String, nonce: String, device: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(unhex(secretHex), "HmacSHA256"))
        return hex(mac.doFinal("prism-handoff|$nonce|$device".toByteArray()))
    }

    fun matches(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
