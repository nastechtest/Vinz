package com.prism.music.data.innertube

import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.SongItem
import java.text.Normalizer
import kotlin.math.max

/** "Top results": the single best match, then each kind of result, best-matching kind first. */
data class TopSearch(val top: BrowseItem?, val sections: List<Pair<SearchFilter, List<BrowseItem>>>) {
    val isEmpty: Boolean get() = top == null && sections.isEmpty()
}

/**
 * Ranks results from YouTube Music's per-kind searches. Its own mixed search leans on
 * whatever is trending ("AM" turns up podcasts), so Prism asks for songs, albums, artists,
 * playlists and videos separately and judges how well each matches what was typed, and
 * how well it fits what the listener plays ([Taste]).
 */
object SearchRank {
    /** An artist the listener knows, with how much (0..1). */
    data class KnownArtist(val id: String?, val name: String, val thumbnail: String?, val familiarity: Double)

    /**
     * What the listener plays, for leaning results their way: "A Day" should find A Day to
     * Remember for someone who plays them, before an EP of that name. Built on the phone from
     * play history, likes and the library ([com.prism.music.data.TasteRepository.searchTaste]).
     */
    class Taste(artists: List<KnownArtist>, val songs: Map<String, Double>) {
        val artists: Map<String, KnownArtist> = artists.groupBy { norm(it.name) }.mapValues { (_, l) -> l.maxBy { it.familiarity } }
        fun artist(name: String): Double = artists[norm(name)]?.familiarity ?: 0.0
        companion object { val NONE = Taste(emptyList(), emptyMap()) }
    }

    /** How far a result the listener knows well can climb past a closer match they don't. */
    private const val TASTE_BOOST = 0.45

    /** 0..1: how much the listener plays [item] (or its artist). */
    fun familiarity(item: BrowseItem, taste: Taste): Double = when (item) {
        is ArtistItem -> taste.artist(item.title)
        is SongItem -> maxOf(taste.songs[item.id] ?: 0.0, 0.6 * (item.song.artists.maxOfOrNull { taste.artist(it.name) } ?: 0.0))
        // "Album • A Day to Remember • 2009"
        is AlbumItem -> 0.7 * (item.subtitle.split(" • ").maxOfOrNull { taste.artist(it) } ?: 0.0)
        else -> 0.0
    }

    /** Every typed word is a whole word of the title ("a day" in "A Day to Remember", not "halo" in "Halocene"). */
    private fun wholeWords(query: String, title: String): Boolean {
        val t = norm(title).split(" ").toSet()
        return norm(query).split(" ").all { it in t }
    }

    private val limits = mapOf(
        SearchFilter.SONGS to 6, SearchFilter.ALBUMS to 8, SearchFilter.ARTISTS to 6,
        SearchFilter.PLAYLISTS to 8, SearchFilter.VIDEOS to 6,
    )

    fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .replace("&", " and ")
        .replace(Regex("[^\\p{L}\\p{N} ]"), "")
        .replace(Regex("\\s+"), " ").trim()

    /** "1989 (Taylor's Version)" and "Song - Remastered 2011" match "1989" and "Song". */
    private fun core(title: String) = norm(title.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").replace(Regex("\\s+-\\s+.*$"), ""))

    /** 0..1: how well [title] (with [extra], e.g. the artist) matches the query. */
    fun match(query: String, title: String, extra: String = ""): Double {
        val q = norm(query)
        if (q.isEmpty()) return 0.0
        val t = norm(title)
        if (t == q) return 1.0
        // "After Hours (Instrumental)" by a cover act isn't the After Hours that was meant.
        if (core(title) == q) return if (knockoffMarks(title, q).isNotEmpty()) 0.7 else 0.97
        val qWords = q.split(" ")
        val all = norm("$title $extra").split(" ").toSet()
        // Title and artist typed together ("do i wanna know arctic monkeys").
        if (qWords.all { it in all } && core(title).split(" ").all { it in qWords }) return 0.95
        // An abbreviation: "adtr" is A Day To Remember, "rhcp" Red Hot Chili Peppers. Exact titles above still win
        // (an artist really called "NF"); a song by the abbreviated artist counts for something too.
        if (initialsOf(title) == q) return 0.9
        if (t.startsWith(q)) return 0.75
        if (qWords.all { w -> all.any { it.startsWith(w) } }) return 0.6
        if (extra.isNotBlank() && extra.split(" • ", ", ", " & ").any { initialsOf(it) == q }) return 0.55
        if (t.contains(q)) return 0.45
        return 0.2
    }

    /** Words that make a version something other than the song: karaoke, covers, sped-up edits, flips… */
    private val knockoff = Regex(
        "\\b(instrumental|karaoke|cover|tribute|sped up|slowed|nightcore|8d|lofi|lo fi|piano version|made famous|flip|remix|mashup|bootleg)\\b",
    )

    /** Such words in [title]'s brackets or " - …" tail that the query [q] (normalised) doesn't ask for. */
    private fun knockoffMarks(title: String, q: String): List<String> {
        val tails = Regex("[(\\[]([^)\\]]*)[)\\]]|\\s-\\s(.*)$").findAll(title).map { norm(it.value) }.joinToString(" ")
        val asked = q.split(" ").toSet()
        return knockoff.findAll(tails).map { it.value }.filter { m -> m.split(" ").any { it !in asked } }.toList()
    }

    /** "A Day To Remember" -> "adtr"; null for a single word (its initial is no abbreviation). */
    private fun initialsOf(name: String): String? {
        val words = core(name).split(" ").filter { it.isNotEmpty() }
        if (words.size < 2) return null
        return words.joinToString("") { it.take(1) }
    }

    /**
     * How strongly YouTube's own "Top result" card counts: above a near match or a literal title from someone unknown
     * ("Bohemian", an EP, against Queen); a match from an artist the listener plays can still climb past it.
     */
    private const val CARD_PRIOR = 0.95

    /**
     * "444M monthly audience", "3.55K subscribers" -> a number. In other languages too ("월간 시청자 7250만명",
     * "65 Mio. Zuschauer/Monat", "72,5 jt audiens bulanan", "มีผู้ชมรายเดือน 65 ล้าน คน"): the first number in the
     * artist's subtitle with the size word right after it.
     */
    internal fun audience(subtitle: String): Long {
        val text = subtitle.substringAfter(" • ", subtitle)
        val m = audienceRegex.find(text) ?: return 0
        val raw = m.groupValues[1]
        // "72,5" / "7.25" are decimals, "789 000" / "1,234" thousands.
        val number = Regex("[.,](\\d{1,2})$").find(raw)?.let { d ->
            (raw.dropLast(d.value.length).replace(Regex("[.,  ]"), "") + "." + d.groupValues[1]).toDoubleOrNull()
        } ?: raw.replace(Regex("[.,  ]"), "").toDoubleOrNull() ?: return 0
        val unit = m.groupValues[2].lowercase().trimEnd('.')
        val mult = audienceUnits.entries.firstOrNull { (k, _) -> unit.startsWith(k) }?.value ?: 1.0
        return (number * mult).toLong()
    }

    // Letters plus combining marks: Thai "ล้าน" and Hindi "लाख" carry vowel signs.
    private val audienceRegex = Regex("(\\d[\\d.,  ]*\\d|\\d)\\s*([\\p{L}\\p{M}.]*)")
    private val audienceUnits = linkedMapOf(
        // Checked in order by prefix: longer words before the shorter ones they start with ("mil" before "mi", "mln" before "m").
        "mrd" to 1e9, "bn" to 1e9, "億" to 1e8, "亿" to 1e8, "억" to 1e8, "crore" to 1e7, "क" to 1e7, "lakh" to 1e5, "लाख" to 1e5,
        "mil" to 1e3, "mio" to 1e6, "mln" to 1e6, "млн" to 1e6, "mn" to 1e6, "mi" to 1e6, "jt" to 1e6, "tr" to 1e6, "ล้าน" to 1e6,
        "مليون" to 1e6, "ألف" to 1e3, "萬" to 1e4, "万" to 1e4, "만" to 1e4, "тыс" to 1e3, "тис" to 1e3, "tys" to 1e3, "tsd" to 1e3,
        "rb" to 1e3, "k" to 1e3, "m" to 1e6, "b" to 1e9,
    )

    /** How strongly each kind of result answers an exact match. */
    private fun weight(item: BrowseItem): Double = when (item) {
        is ArtistItem -> if (audience(item.subtitle) >= 1_000_000) 1.0 else 0.8
        is AlbumItem -> if (item.subtitle.startsWith("Single")) 0.8 else 0.93
        is SongItem -> if (item.song.isVideo) 0.72 else 0.86
        is PlaylistItem -> 0.62
        else -> 0.5
    }

    fun score(query: String, item: BrowseItem, position: Int, taste: Taste = Taste.NONE): Double {
        val extra = when (item) {
            is SongItem -> item.song.artistText
            else -> item.subtitle
        }
        val m = match(query, item.title, extra)
        val fam = if (m >= 0.6) familiarity(item, taste) else 0.0
        // An artist the listener plays counts as a big one, whatever YouTube says of their audience.
        val w = if (item is ArtistItem && fam > 0.0) 1.0 else weight(item)
        // YouTube's own order within each kind stands in for popularity (a cover album ranks below the original).
        val base = m * w - position * 0.08
        // What the listener plays climbs, but only when it really answers what was typed;
        // half a word ("halo" of "Halocene") only nudges it.
        if (fam <= 0.0) return base
        val fit = if (m >= 0.95 || wholeWords(query, item.title)) 1.0 else 0.2
        return base + TASTE_BOOST * fam * fit
    }

    /**
     * Artists the listener plays whose name starts with every word typed ("a day" → A Day to
     * Remember), for when YouTube's artist search leaves them out.
     */
    fun knownArtists(query: String, taste: Taste): List<ArtistItem> {
        val q = norm(query)
        // Too short to mean one artist ("the" starts half the library).
        if (q.length < 4) return emptyList()
        return taste.artists.values
            .filter { a -> a.id != null && a.familiarity >= 0.5 && norm(a.name).let { n -> n == q || n.startsWith("$q ") } }
            .sortedByDescending { it.familiarity }
            .take(2)
            .map { ArtistItem(it.id!!, it.name, "Artist", it.thumbnail) }
    }

    /**
     * Completions from the listener's own world, for the suggestions under the search box while typing: recent searches
     * and known artists that start with what's typed (or whose initials it is, "adt" → A Day To Remember), best known first.
     */
    fun predict(query: String, taste: Taste, recents: List<String>, limit: Int = 3): List<String> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        fun fits(name: String): Boolean {
            val n = norm(name)
            return n != q && (n.startsWith(q) || n.split(" ").any { it.startsWith(q) && q.length >= 3 } ||
                (q.length >= 2 && !q.contains(' ') && initialsOf(name)?.startsWith(q) == true))
        }
        val fromRecents = recents.filter { norm(it).startsWith(q) && norm(it) != q }
        val fromArtists = taste.artists.values.filter { fits(it.name) }.sortedByDescending { it.familiarity }.map { it.name }
        return (fromRecents + fromArtists).distinctBy { norm(it) }.take(limit)
    }

    /** [song] is its album's title track and the query names exactly that ("after hours" → After Hours). */
    fun namesItsAlbum(query: String, song: com.prism.music.data.model.Song): Boolean {
        val album = song.album ?: return false
        return core(album.title) == core(song.title) && core(album.title) == norm(query)
    }

    /** [album] becomes the top result; the song that was there leads the Songs section instead. */
    fun withAlbumOnTop(r: TopSearch, album: AlbumItem): TopSearch {
        val song = r.top as? SongItem
        val sections = r.sections.map { (f, items) ->
            val rest = items.filter { it.id != album.id }
            f to if (f == SearchFilter.SONGS && song != null) listOf(song) + rest else rest
        }.filter { it.second.isNotEmpty() }
        val withSong = if (song == null || sections.any { it.first == SearchFilter.SONGS }) sections
            else listOf(SearchFilter.SONGS to listOf<BrowseItem>(song)) + sections
        return TopSearch(album, withSong)
    }

    /**
     * Ranks [results]; live streams ([SearchFilter.LIVE]) always get a row of their own, last, and don't
     * compete for the top spot unless there's nothing else.
     */
    fun rank(query: String, results: List<Pair<SearchFilter, List<BrowseItem>>>, taste: Taste = Taste.NONE, card: BrowseItem? = null): TopSearch {
        val live = results.firstOrNull { it.first == SearchFilter.LIVE }?.second.orEmpty().distinctBy { it.id }.take(10)
        val ranked = rankMusic(query, results.filter { it.first != SearchFilter.LIVE }, taste, card?.takeUnless { it is SongItem && it.song.isLive })
        if (live.isEmpty()) return ranked
        val top = ranked.top ?: live.first()
        val sections = ranked.sections.toMutableList()
        val row = live.filter { it != top }
        if (row.isNotEmpty()) sections.add(SearchFilter.LIVE to row)
        return TopSearch(top, sections)
    }

    /**
     * YouTube's card, signed out, is sometimes a knock-off ("after hours" → an instrumental cover, "blinding lights" →
     * a reggae flip): a version the query didn't ask for gets no head start.
     */
    private fun trustCard(query: String, card: BrowseItem): Boolean = knockoffMarks(card.title, norm(query)).isEmpty()

    /** The section YouTube's card belongs in. */
    private fun kindOf(item: BrowseItem): SearchFilter? = when (item) {
        is ArtistItem -> SearchFilter.ARTISTS
        is AlbumItem -> SearchFilter.ALBUMS
        is PlaylistItem -> SearchFilter.PLAYLISTS
        is SongItem -> if (item.song.isVideo) SearchFilter.VIDEOS else SearchFilter.SONGS
        else -> null
    }

    private fun rankMusic(query: String, results: List<Pair<SearchFilter, List<BrowseItem>>>, taste: Taste, card: BrowseItem? = null): TopSearch {
        // The listener's own artists lead YouTube's artist results when they fit and are missing from them.
        val known = knownArtists(query, taste)
        val withKnown = results.map { (f, items) ->
            if (f != SearchFilter.ARTISTS) f to items
            else f to (known.filter { k -> items.none { it.id == k.id || norm(it.title) == norm(k.title) } } + items)
        }.let { list ->
            if (list.any { it.first == SearchFilter.ARTISTS } || known.isEmpty()) list
            else list + (SearchFilter.ARTISTS to known)
        }
        // YouTube's card also leads its own kind's row (its per-kind search sometimes leaves it out, or low down).
        // A music video as the card ("humble kendrick" → HUMBLE.'s video) gives way to the song itself when the
        // song results have it: Prism opens videos in video mode, and most searches are for the music.
        val songs = results.firstOrNull { it.first == SearchFilter.SONGS }?.second.orEmpty().filterIsInstance<SongItem>()
        val asSong = (card as? SongItem)?.takeIf { it.song.isVideo }?.let { v ->
            songs.firstOrNull { s -> core(s.title) == core(v.title) && s.song.primaryArtist.isNotBlank() && norm(s.song.primaryArtist) == norm(v.song.primaryArtist) }
        }
        val trusted = (asSong ?: card)?.takeIf { trustCard(query, it) }
        val cardKind = trusted?.let(::kindOf)
        val withCard = if (trusted == null || cardKind == null) withKnown else {
            val card: BrowseItem = trusted
            val list = withKnown.map { (f, items) -> if (f == cardKind) f to (listOf(card) + items.filter { it.id != card.id }) else f to items }
            if (list.any { it.first == cardKind }) list else list + (cardKind to listOf(card))
        }
        val trimmed = withCard.mapNotNull { (f, items) ->
            items.distinctBy { it.id }.take(limits[f] ?: 6).takeIf { it.isNotEmpty() }?.let { f to it }
        }
        val scored = trimmed.map { (f, items) ->
            Triple(f, items, items.mapIndexed { i, it ->
                // The card sits first in its row by Prism's doing, so it's scored as YouTube's first, at least at the prior.
                if (trusted != null && it.id == trusted.id) maxOf(score(query, it, 0, taste), CARD_PRIOR)
                else score(query, it, i, taste)
            })
        }
        var top = scored.flatMap { (_, items, scores) -> items.zip(scores).take(3) }.maxByOrNull { it.second }?.first
        // A title track ("After Hours" by The Weeknd) gives way to the album it names.
        (top as? SongItem)?.song?.let { song ->
            val albums = trimmed.firstOrNull { it.first == SearchFilter.ALBUMS }?.second.orEmpty()
            albums.filterIsInstance<AlbumItem>().firstOrNull { a ->
                !a.subtitle.startsWith("Single") && core(a.title) == core(song.title) && core(a.title) == norm(query) &&
                    song.primaryArtist.isNotBlank() && a.subtitle.contains(song.primaryArtist, ignoreCase = true)
            }?.let { top = it }
        }
        // With an artist on top ("adtr" → A Day To Remember), their songs and albums are what's wanted next.
        val topArtist = (top as? ArtistItem)?.title?.let(::norm)
        fun byTopArtist(item: BrowseItem): Boolean = topArtist != null && when (item) {
            is SongItem -> item.song.artists.any { norm(it.name) == topArtist }
            is AlbumItem -> item.subtitle.split(" • ").any { norm(it) == topArtist }
            else -> false
        }
        val sections = scored
            .sortedByDescending { (f, items, scores) ->
                // Ranked by what's left once the top result is lifted out of its row.
                val best = items.zip(scores).filter { it.first != top }.maxOfOrNull { it.second } ?: 0.0
                val related = if (items.any(::byTopArtist)) 0.8 else 0.0
                // Songs stay near the top: they're what most searches are for.
                maxOf(best, related, if (f == SearchFilter.SONGS) 0.5 else 0.0)
            }
            .map { (f, items, _) -> f to items.filter { it != top } }
            .filter { it.second.isNotEmpty() }
        return TopSearch(top, sections)
    }
}
