package com.prism.music.connect

/** This Prism's side of a handoff: who it is, its pairings, and its player. */
interface HandoffLocal {
    val name: String
    val device: String
    fun secretFor(device: String): String?
    fun savePairing(device: String, name: String, secret: String)
    fun forget(device: String)
    /** Shows [code] on this device for [from] to type in (null: put it away). */
    fun showCode(code: String?, from: String)
    /** What's playing here, or null. */
    fun snapshot(): HandoffSnapshot?
    /** Plays [s] here; null once it has, else why it couldn't (said to the other device). [from] is the other device's name. */
    fun takeOver(s: HandoffSnapshot, from: String): String?
    /** The other device has it now: fade out and pause. */
    fun stepAside()
}

sealed interface HandoffResult {
    /** [note]: something to say (songs that couldn't come). */
    data class Done(val note: String? = null) : HandoffResult
    data class Failed(val reason: String) : HandoffResult
    /** The key this side had was refused: pair again. */
    data object Unpaired : HandoffResult
    data object Cancelled : HandoffResult
}

/**
 * The two ends of a handoff over one connection. The receiving side ([serve], the device that's "available for
 * handoff") answers a hello with a fresh challenge; the caller proves it's paired with an HMAC of it (the shared key is
 * never sent again), or asks to pair: the receiving side shows a six-digit code, the caller types it, and the key is
 * handed over once. Then the caller either moves its playback across ([HandoffCommand.MOVE]) or pulls the other's here
 * ([HandoffCommand.PULL]); the side giving it up steps aside only once the other says it's playing.
 */
class HandoffPeer(private val local: HandoffLocal, private val clock: () -> Long = System::currentTimeMillis) {
    private val pairAsks = java.util.ArrayDeque<Long>()

    private fun pairAllowed(): Boolean = synchronized(pairAsks) {
        val now = clock()
        while (pairAsks.isNotEmpty() && pairAsks.first < now - 60_000) pairAsks.removeFirst()
        if (pairAsks.size >= PAIR_ASKS_PER_MINUTE) return false
        pairAsks.addLast(now)
        true
    }

    /** The receiving side, for one connection. Blocking. */
    fun serve(link: HLink) {
        link.timeout(8_000)
        val hello = link.read() as? HMsg.Hello ?: return
        if (hello.version != Handoff.VERSION) { link.send(HMsg.Denied("Both devices need the latest Vinz")); return }
        val them = hello.name.trim().take(60).ifBlank { "Another device" }
        val nonce = HandoffKeys.newNonce()
        link.send(HMsg.Challenge(local.name, local.device, nonce))
        when (val m = link.read()) {
            is HMsg.Auth -> {
                val secret = local.secretFor(hello.device)
                if (secret == null || !HandoffKeys.matches(HandoffKeys.mac(secret, nonce, hello.device), m.mac)) {
                    Thread.sleep(400)
                    link.send(HMsg.Denied("$them isn't paired with ${local.name} any more", unpaired = true))
                    return
                }
                link.send(HMsg.Ok)
            }
            HMsg.PairRequest -> {
                if (!pairAllowed()) { link.send(HMsg.Denied("Too many pairing tries. Wait a minute.")); return }
                val code = HandoffKeys.newCode()
                local.showCode(code, them)
                try {
                    link.send(HMsg.EnterCode)
                    link.timeout(PAIR_WAIT_MS)
                    val p = link.read() as? HMsg.Pair ?: return
                    if (!HandoffKeys.matches(p.code.filter { it.isDigit() }, code)) {
                        Thread.sleep(700)
                        link.send(HMsg.Denied("That code didn't match. Try again for a new one."))
                        return
                    }
                    val secret = HandoffKeys.newSecret()
                    local.savePairing(hello.device, them, secret)
                    link.send(HMsg.Paired(secret))
                } finally {
                    local.showCode(null, them)
                }
            }
            else -> return
        }
        link.timeout(20_000)
        when (val cmd = link.read()) {
            is HMsg.Move -> {
                val s = cmd.snapshot.cleaned()
                if (s == null) link.send(HMsg.Denied("What's playing on $them is only on $them"))
                else link.send(local.takeOver(s, them)?.let { HMsg.Denied(it) } ?: HMsg.Moved)
            }
            HMsg.Pull -> {
                val s = local.snapshot()
                if (s == null) { link.send(HMsg.Denied("Nothing's playing on ${local.name}")); return }
                link.send(HMsg.Snap(s))
                if (link.read() == HMsg.Moved) local.stepAside()
            }
            else -> Unit
        }
    }

    /**
     * The calling side: [command] on the device at the other end of [link]. [askCode] is called (blocking) when they
     * need pairing first, for the code the other device shows; null cancels. Blocking.
     */
    fun call(link: HLink, command: HandoffCommand, askCode: (String) -> String?): HandoffResult {
        link.timeout(8_000)
        if (!link.send(HMsg.Hello(local.name, local.device))) return HandoffResult.Failed("Couldn't reach it")
        val ch = when (val m = link.read()) {
            is HMsg.Challenge -> m
            is HMsg.Denied -> return HandoffResult.Failed(m.reason)
            else -> return HandoffResult.Failed("It didn't answer")
        }
        val them = ch.name.trim().take(60).ifBlank { "the other device" }
        val secret = local.secretFor(ch.device)
        if (secret != null) {
            link.send(HMsg.Auth(HandoffKeys.mac(secret, ch.nonce, local.device)))
            when (val r = link.read()) {
                HMsg.Ok -> Unit
                is HMsg.Denied -> {
                    if (r.unpaired) { local.forget(ch.device); return HandoffResult.Unpaired }
                    return HandoffResult.Failed(r.reason)
                }
                else -> return HandoffResult.Failed("$them didn't answer")
            }
        } else {
            link.send(HMsg.PairRequest)
            when (val r = link.read()) {
                HMsg.EnterCode -> Unit
                is HMsg.Denied -> return HandoffResult.Failed(r.reason)
                else -> return HandoffResult.Failed("$them didn't answer")
            }
            link.timeout(PAIR_WAIT_MS)
            val code = askCode(them) ?: return HandoffResult.Cancelled
            link.send(HMsg.Pair(code))
            link.timeout(10_000)
            when (val r = link.read()) {
                is HMsg.Paired -> local.savePairing(ch.device, them, r.secret)
                is HMsg.Denied -> return HandoffResult.Failed(r.reason)
                else -> return HandoffResult.Failed("$them didn't answer")
            }
        }
        link.timeout(20_000)
        return when (command) {
            HandoffCommand.MOVE -> {
                val s = local.snapshot() ?: return HandoffResult.Failed("Nothing's playing here")
                val clean = s.cleaned() ?: return HandoffResult.Failed("This song is a file on this device: it can't move")
                link.send(HMsg.Move(s))
                when (val r = link.read()) {
                    HMsg.Moved -> {
                        local.stepAside()
                        HandoffResult.Done(s.leftOut(clean).takeIf { it > 0 }?.let { "$it ${if (it == 1) "song" else "songs"} on this device stayed behind" })
                    }
                    is HMsg.Denied -> HandoffResult.Failed(r.reason)
                    else -> HandoffResult.Failed("$them didn't start playing")
                }
            }
            HandoffCommand.PULL -> {
                link.send(HMsg.Pull)
                when (val r = link.read()) {
                    is HMsg.Snap -> {
                        val clean = r.snapshot.cleaned() ?: return HandoffResult.Failed("What's playing on $them is only on $them")
                        local.takeOver(clean, them)?.let { return HandoffResult.Failed(it) }
                        link.send(HMsg.Moved)
                        HandoffResult.Done(r.snapshot.leftOut(clean).takeIf { it > 0 }?.let { "$it ${if (it == 1) "song" else "songs"} stayed on $them" })
                    }
                    is HMsg.Denied -> HandoffResult.Failed(r.reason)
                    else -> HandoffResult.Failed("$them didn't answer")
                }
            }
        }
    }

    companion object {
        const val PAIR_WAIT_MS = 180_000
        const val PAIR_ASKS_PER_MINUTE = 5
    }
}

enum class HandoffCommand { MOVE, PULL }
