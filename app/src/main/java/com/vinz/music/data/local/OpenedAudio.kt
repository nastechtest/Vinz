package com.vinz.music.data.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.vinz.music.data.model.AlbumRef
import com.vinz.music.data.model.Song

/**
 * "Open with Vinz" (1.8.5, Reddit): an audio file opened from a file manager or a chat plays in Vinz as a one-song
 * queue, straight from the file it came as (nothing is copied, no storage permission is asked for: the opener's read
 * grant is enough). It's a local song ([Song.isLocal]) whose id carries the file's address, so the player finds it the
 * way it finds Local music's files ([LocalMusic.uriFor]).
 */
object OpenedAudio {
    /** Types besides audio/... that file managers give audio files. */
    val OTHER_TYPES = setOf("application/ogg", "application/x-flac")

    /** A file (content:// or file://) of an audio type, opened with Vinz. */
    fun accepts(action: String?, uri: Uri?, type: String?): Boolean {
        if (action != Intent.ACTION_VIEW || uri == null) return false
        if (uri.scheme != "content" && uri.scheme != "file") return false
        val t = type?.lowercase() ?: return false
        return t.startsWith("audio/") || t in OTHER_TYPES
    }

    /**
     * Another app can name Vinz's own files to it ("file:///data/data/<Vinz>/…", or a content:// address on one of
     * Vinz's own providers): Vinz would read them with its own rights and keep the address in its queue and history.
     * True for those, which are never opened.
     */
    fun vinzsOwn(context: Context, uri: Uri): Boolean {
        val pkg = context.packageName
        val dirs = buildList {
            runCatching { add(context.dataDir.path) }
            runCatching { add(context.filesDir.path) }
            runCatching { add(context.cacheDir.path) }
            runCatching { context.getExternalFilesDirs(null).forEach { d -> d?.parentFile?.let { add(it.path) } } }
            runCatching { context.externalCacheDirs.forEach { d -> d?.parentFile?.let { add(it.path) } } }
            runCatching { context.obbDirs.forEach { d -> d?.let { add(it.path) } } }
        }
        val authorities = runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.GET_PROVIDERS)
            info.providers?.flatMap { it.authority?.split(';').orEmpty() }?.toSet()
        }.getOrNull().orEmpty()
        return vinzsOwn(uri, pkg, dirs, authorities)
    }

    /**
     * [vinzsOwn] for the app [pkg] with its data folders [dirs] and its providers' [authorities]. A file's path is taken
     * as it really is ([canonical]: "..", and links followed), and one that can't be is refused too.
     */
    internal fun vinzsOwn(
        uri: Uri,
        pkg: String,
        dirs: List<String>,
        authorities: Set<String>,
        canonical: (String) -> String? = { p -> runCatching { java.io.File(p).canonicalPath }.getOrNull() },
    ): Boolean = when (uri.scheme?.lowercase()) {
        "content" -> {
            // "10@authority" names a provider for another user of the phone: the authority is after the '@'.
            val a = uri.authority?.substringAfterLast('@')?.lowercase()
            a == null || a == pkg.lowercase() || a.startsWith(pkg.lowercase() + ".") || authorities.any { it.lowercase() == a }
        }
        "file" -> {
            val real = uri.path?.let(canonical)
            real == null || real.split('/', '\\').any { it == pkg } || dirs.any { d ->
                canonical(d)?.trimEnd('/', '\\')?.takeIf { it.isNotEmpty() }
                    ?.let { own -> real == own || real.startsWith("$own/") || real.startsWith("$own\\") } == true
            }
        }
        else -> false
    }

    /** The file an opened song plays from; null for any other song (Local music's are MediaStore ids). */
    fun uriOf(id: String): Uri? {
        if (!id.startsWith(Song.LOCAL_PREFIX)) return null
        val rest = id.removePrefix(Song.LOCAL_PREFIX)
        return if (rest.startsWith("content://") || rest.startsWith("file://")) Uri.parse(rest) else null
    }

    /** The song for the file at [uri]: its tags when it has them, else its file name ([displayName], else the path's end). */
    fun song(uri: Uri, displayName: String?, title: String?, artist: String?, album: String?, durationMs: Long?): Song {
        val name = (displayName?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString())
            .let { n -> n.substringBeforeLast('.').takeIf { it.isNotBlank() } ?: n }
        return Song(
            id = Song.LOCAL_PREFIX + uri.toString(),
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: name,
            artists = LocalMusic.artistsOf(artist),
            album = album?.trim()?.takeIf { it.isNotEmpty() && it != "<unknown>" }?.let { AlbumRef(it) },
            durationSec = ((durationMs ?: 0L) / 1000).toInt(),
        )
    }

    /**
     * Reads the file's name and tags (off the main thread: it opens the file). A file whose tags can't be read is still
     * tried by its name: if Android can't play it either, the player says so as for any song.
     */
    fun read(context: Context, uri: Uri): Song {
        // A grant that may be kept (a document picked from the system's Files) lets it play again after a restart.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val displayName = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            fun tag(key: Int) = runCatching { r.extractMetadata(key) }.getOrNull()
            song(
                uri, displayName,
                tag(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE),
                tag(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: tag(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                tag(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM),
                tag(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            )
        } catch (e: Exception) {
            song(uri, displayName, null, null, null, null)
        } finally {
            runCatching { r.release() }
        }
    }
}
