package com.prism.music.data

import android.content.Context
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.CollectionKind
import com.prism.music.data.model.CollectionPage
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.PodcastItem
import com.prism.music.data.model.Song
import kotlinx.serialization.Serializable
import java.io.File

/**
 * What Prism keeps so the library works without a network (#55): the account's playlists, albums and artists as last
 * synced, and the playlists and albums it has opened, with their songs. Offline, Library lists them as usual, and a
 * playlist opens from its saved copy and plays its downloaded songs (the rest show, greyed out).
 */
class OfflineLibrary(context: Context) {
    private val dir = File(context.filesDir, "offline").apply { mkdirs() }
    private val pagesDir = File(dir, "pages").apply { mkdirs() }
    private val listsFile = File(dir, "library.json")

    @Serializable
    private data class Item(
        val kind: String, val id: String, val title: String, val subtitle: String, val thumbnail: String? = null,
        val playlistId: String? = null, val isMix: Boolean = false, val seedVideoId: String? = null, val params: String? = null,
    )

    @Serializable
    private data class Lists(val playlists: List<Item> = emptyList(), val albums: List<Item> = emptyList(), val artists: List<Item> = emptyList())

    @Serializable
    private data class Page(
        val id: String, val album: Boolean, val title: String, val subtitle: String, val secondSubtitle: String = "",
        val description: String? = null, val thumbnail: String? = null, val songs: List<Song>, val playlistId: String? = null,
        val artists: List<ArtistRef> = emptyList(), val owned: Boolean = false, val privacy: String? = null,
        val explicit: Boolean = false, val artistThumbnail: String? = null,
    )

    data class Library(val playlists: List<BrowseItem>, val albums: List<BrowseItem>, val artists: List<BrowseItem>)

    private fun BrowseItem.toItem(): Item? = when (this) {
        is PlaylistItem -> Item("playlist", id, title, subtitle, thumbnail, isMix = isMix, seedVideoId = seedVideoId, params = params)
        is AlbumItem -> Item("album", id, title, subtitle, thumbnail, playlistId = playlistId)
        is ArtistItem -> Item("artist", id, title, subtitle, thumbnail)
        is PodcastItem -> Item("podcast", id, title, subtitle, thumbnail)
        else -> null
    }

    private fun Item.toBrowse(): BrowseItem? = when (kind) {
        "playlist" -> PlaylistItem(id, title, subtitle, thumbnail, isMix, seedVideoId, params)
        "album" -> AlbumItem(id, title, subtitle, thumbnail, playlistId)
        "artist" -> ArtistItem(id, title, subtitle, thumbnail)
        "podcast" -> PodcastItem(id, title, subtitle, thumbnail)
        else -> null
    }

    /** The library as last synced (null before the first sync). */
    fun library(): Library? = runCatching {
        val l = InnerTube.json.decodeFromString(Lists.serializer(), listsFile.readText())
        Library(l.playlists.mapNotNull { it.toBrowse() }, l.albums.mapNotNull { it.toBrowse() }, l.artists.mapNotNull { it.toBrowse() })
    }.getOrNull()

    fun saveLibrary(playlists: List<BrowseItem>, albums: List<BrowseItem>, artists: List<BrowseItem>) {
        val l = Lists(playlists.mapNotNull { it.toItem() }, albums.mapNotNull { it.toItem() }, artists.mapNotNull { it.toItem() })
        runCatching { atomicWrite(listsFile, InnerTube.json.encodeToString(Lists.serializer(), l)) }
    }

    private fun pageFile(id: String) = File(pagesDir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    /** Keeps a playlist or album (opened as [id]) with its songs, for opening offline. */
    fun savePage(id: String, page: CollectionPage) {
        if (page.songs.isEmpty()) return
        val p = Page(
            page.id, page.kind == CollectionKind.ALBUM, page.title, page.subtitle, page.secondSubtitle, page.description, page.thumbnail,
            page.songs, page.playlistId, page.artists, page.owned, page.privacy, page.explicit, page.artistThumbnail,
        )
        runCatching { atomicWrite(pageFile(id), InnerTube.json.encodeToString(Page.serializer(), p)) }
        trimPages()
    }

    /** The saved copy of the playlist or album opened as [id], if there is one. */
    fun page(id: String): CollectionPage? = runCatching {
        val p = InnerTube.json.decodeFromString(Page.serializer(), pageFile(id).readText())
        CollectionPage(
            id = p.id, kind = if (p.album) CollectionKind.ALBUM else CollectionKind.PLAYLIST, title = p.title, subtitle = p.subtitle,
            secondSubtitle = p.secondSubtitle, description = p.description, thumbnail = p.thumbnail, songs = p.songs,
            playlistId = p.playlistId, artists = p.artists, owned = p.owned, privacy = p.privacy, explicit = p.explicit,
            artistThumbnail = p.artistThumbnail,
        )
    }.getOrNull()

    /** Every kept playlist and album with the id it was opened as, last kept first. Reads files: call off the main thread. */
    fun pages(): List<Pair<String, CollectionPage>> = pagesDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .sortedByDescending { it.lastModified() }
        .mapNotNull { f -> f.name.removeSuffix(".json").let { id -> page(id)?.let { id to it } } }

    /** Forgets everything kept (after signing out). */
    fun clear() {
        listsFile.delete()
        pagesDir.listFiles()?.forEach { it.delete() }
    }

    /** When the copy of [id] was saved (0 if there's none). */
    fun savedAt(id: String): Long = pageFile(id).lastModified()

    /** Every saved page's cover and songs' covers, so the covers they need are kept. */
    fun covers(): List<String?> = pagesDir.listFiles()?.flatMap { f ->
        runCatching { InnerTube.json.decodeFromString(Page.serializer(), f.readText()).let { listOf(it.thumbnail) } }.getOrDefault(emptyList())
    }.orEmpty() + (library()?.let { l -> (l.playlists + l.albums + l.artists).map { it.thumbnail } } ?: emptyList())

    /** The most recently saved pages stay; the rest go (each is a few hundred KB at most). */
    private fun trimPages() {
        val files = pagesDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_PAGES).forEach { it.delete() }
    }

    private fun atomicWrite(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private companion object {
        const val MAX_PAGES = 400
    }
}
