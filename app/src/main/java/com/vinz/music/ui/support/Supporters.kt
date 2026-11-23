package com.vinz.music.ui.support

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Supporters list (plan-1.8.7.6 §4.3), shipped inside the app as `assets/supporters.json`: names only, in the order
 * people first supported. [members] (monthly "Vinz Supporter" members) are listed first, under their own heading.
 * Only public donations, and only names the owner approved for the release; nothing is ever fetched.
 */
@Serializable
data class Supporters(val members: List<String> = emptyList(), val supporters: List<String> = emptyList()) {
    val isEmpty: Boolean get() = members.isEmpty() && supporters.isEmpty()

    companion object {
        const val ASSET = "supporters.json"
        private val json = Json { ignoreUnknownKeys = true }

        /** The list in [text], or null when it isn't one. Blank names are left out. */
        fun parse(text: String): Supporters? = runCatching {
            json.decodeFromString(serializer(), text).let { s ->
                Supporters(s.members.map { it.trim() }.filter { it.isNotEmpty() }, s.supporters.map { it.trim() }.filter { it.isNotEmpty() })
            }
        }.getOrNull()

        /** The list shipped with this build (empty if it can't be read). */
        fun read(context: Context): Supporters =
            runCatching { context.assets.open(ASSET).use { it.readBytes().decodeToString() } }.getOrNull()?.let(::parse) ?: Supporters()
    }
}
