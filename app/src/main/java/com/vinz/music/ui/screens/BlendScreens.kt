package com.vinz.music.ui.screens

import com.vinz.music.ui.components.VinzButton
import com.vinz.music.ui.components.VinzOutlinedButton
import com.vinz.music.ui.components.VinzTextButton
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.vinz.music.data.blend.Blend
import com.vinz.music.data.blend.BlendCode
import com.vinz.music.data.blend.BlendSong
import com.vinz.music.data.blend.Blends
import com.vinz.music.data.blend.TasteCode
import com.vinz.music.data.model.SongItem
import com.vinz.music.playback.QueueKind
import com.vinz.music.playback.QueueSource
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.components.Artwork
import com.vinz.music.ui.components.ItemCard
import com.vinz.music.ui.components.RoundAction
import com.vinz.music.ui.components.SectionHeader
import com.vinz.music.ui.components.SubPage
import com.vinz.music.ui.components.recordInto
import com.vinz.music.ui.components.shareLayer
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.LocalUi
import com.vinz.music.ui.theme.VinzIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------------------- QR codes

/** [text] as a QR code (black on white, with its quiet margin), drawn crisp at any size. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
        val pixels = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(bitmap, "QR code", modifier.background(Color.White), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
}

private fun shareCodeText(context: android.content.Context, code: String, name: String) {
    val text = "Let's make a Blend on Vinz! Share this message to Vinz (or scan my code in Library → Blend):\n\n$code"
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Send your Blend code" + if (name.isNotBlank()) " ($name)" else ""))
}

// ---------------------------------------------------------------------------- Blends

/** Library → Blend: your code to show or send, a way to take a friend's, and your Blends. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlendsScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val blends by c.blends.blends.collectAsState(initial = emptyList())
    val pending by c.blends.pending.collectAsState()
    var name by remember { mutableStateOf(c.blends.myName) }
    var nameVersion by remember { mutableStateOf(0) }
    val mine by produceState<TasteCode?>(null, nameVersion) { value = withContext(Dispatchers.IO) { c.blends.myCode() } }
    val code = remember(mine) { mine?.let(BlendCode::encode) }
    var showShared by remember { mutableStateOf(false) }
    // While the code shows, the phone that scans it can send its own straight back over Wi-Fi (BlendExchange): the
    // QR code then also says where to, with a one-time key.
    val qr by produceState<String?>(null, code) { value = code?.let { withContext(Dispatchers.IO) { c.blendExchange.open(it) } } }
    DisposableEffect(Unit) { onDispose { c.blendExchange.close() } }
    LaunchedEffect(Unit) {
        c.blendExchange.made.collect { r ->
            Toast.makeText(context, "${r.name} scanned your code: your Blend is made", Toast.LENGTH_LONG).show()
            nav.go(Routes.blend(r.who))
        }
    }

    fun take(text: String) = scope.launch {
        when (val r = c.blends.import(text)) {
            is Blends.Import.Added -> {
                Toast.makeText(context, if (r.updated) "${r.name}'s Blend is up to date" else "Blend with ${r.name} made", Toast.LENGTH_SHORT).show()
                nav.go(Routes.blend(r.who))
                // Their phone gets the Blend too: this phone's code, as stored with the Blend, goes back to the one shown.
                val myCode = c.blends.get(r.who)?.myCode
                if (myCode != null && com.vinz.music.data.blend.BlendExchange.parseHint(text) != null) c.scope.launch {
                    val back = c.blendExchange.sendBack(text, myCode)
                    if (back is com.vinz.music.data.blend.BlendExchange.SendBack.Done) c.blends.markOnBothPhones(r.who)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            when (back) {
                                is com.vinz.music.data.blend.BlendExchange.SendBack.Done -> "Made on ${back.name}'s phone too"
                                else -> "Couldn't reach ${r.name}'s phone (on another Wi-Fi?). Let them scan your code to have it too."
                            },
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            Blends.Import.Yours -> Toast.makeText(context, "That's your own code", Toast.LENGTH_SHORT).show()
            Blends.Import.NotACode -> Toast.makeText(context, "That isn't a Vinz Blend code", Toast.LENGTH_SHORT).show()
        }
    }

    // A code shared into Vinz from another app: asked about first.
    pending?.let { p ->
        val friend = remember(p) { BlendCode.decode(p) }
        AlertDialog(
            onDismissRequest = { c.blends.pending.value = null },
            title = { Text(if (friend != null) "Blend with ${friend.name}?" else "Not a Blend code") },
            text = {
                Text(
                    when {
                        friend == null -> "That message didn't hold a Vinz Blend code."
                        com.vinz.music.data.blend.BlendExchange.parseHint(p) != null ->
                            "Vinz makes a playlist from your tastes together. Your code (what's under \"What you share\") goes straight back to their phone over Wi-Fi, so you both have it."
                        else -> "Vinz makes a playlist from your tastes together, on this phone. Only your code (what's under \"What you share\") goes to them, if you send it back."
                    },
                )
            },
            confirmButton = { if (friend != null) VinzTextButton({ c.blends.pending.value = null; take(p) }) { Text("Make the Blend") } },
            dismissButton = { VinzTextButton({ c.blends.pending.value = null }) { Text("Not now") } },
        )
    }

    SubPage("Blend", bottomPadding, subtitle = "A playlist of two tastes, made on both phones. Swap codes and it's ready.") {
        item("code") {
            Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.widthIn(max = 340.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(10.dp), contentAlignment = Alignment.Center) {
                    val shown = qr
                    if (shown == null) CircularProgressIndicator(color = Color.Black) else QrCode(shown, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (qr?.contains(com.vinz.music.data.blend.BlendExchange.HINT) == true) "Let a friend scan it in Vinz: on the same Wi-Fi, you both get the Blend at once"
                    else "Your code: let a friend scan it in Vinz",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                com.vinz.music.ui.components.VinzTextField(
                    name, { name = it.take(30) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Your name in Blends") },
                    trailingIcon = { if (name != c.blends.myName) VinzTextButton({ c.blends.myName = name; nameVersion++ }) { Text("Save") } },
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VinzButton({ nav.go(Routes.BLEND_SCAN) }) { Icon(VinzIcons.Camera, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Scan a code") }
                    VinzOutlinedButton({ code?.let { shareCodeText(context, it, name) } }, enabled = code != null) { Icon(VinzIcons.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Send my code") }
                    VinzOutlinedButton({
                        val t = clipboard.getText()?.text
                        if (t.isNullOrBlank()) Toast.makeText(context, "Copy a friend's code first", Toast.LENGTH_SHORT).show() else take(t)
                    }) { Text("Paste a code") }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (showShared) "Hide what you share" else "What you share", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showShared = !showShared }.padding(8.dp),
                )
                mine?.takeIf { showShared }?.let { m ->
                    val sent = remember(code) { code?.let(BlendCode::decode) ?: m }
                    Text(
                        "Your Blend name: ${sent.name}\n" +
                            "Genres: ${sent.genres.entries.joinToString { "${it.key} ${it.value}%" }.ifEmpty { "none yet" }}\n" +
                            "${sent.artists.size} artists: ${sent.artists.joinToString { it.name }}\n" +
                            "${sent.songs.size} songs: ${sent.songs.joinToString { "${it.title} (${it.artist})" }}\n" +
                            "And a random id for this phone, so a friend's phone knows which Blend a new code is for. Nothing else: no history, times or account. " +
                            "Artists you don't play and songs and playlists left out of your taste are left out here too.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (blends.isNotEmpty()) item("h") { Text("Your Blends", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 6.dp)) }
        items(blends.size) { i ->
            val e = blends[i]
            val b = remember(e) { c.blends.blendOf(e) }
            Row(
                Modifier.fillMaxWidth().clickable { nav.go(Routes.blend(e.friendWho)) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BlendCover(e, Modifier.size(56.dp), small = true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("${c.blends.myName.ifBlank { "You" }} + ${e.friendName}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${b?.match ?: 0}% taste match · ${b?.songs?.size ?: 0} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * A Blend's cover: the picture the listener picked for it, or Vinz's own: two overlapping discs, each one person's
 * top song in the Blend, ringed in Vinz's split light on a dark ground lit softly where they meet, with the match in
 * the corner. [small] drops the words for list-sized covers.
 */
@Composable
fun BlendCover(e: com.vinz.music.data.db.BlendEntity, modifier: Modifier = Modifier, small: Boolean = false) {
    val c = LocalContainer.current
    val stamp by c.blends.coverStamp.collectAsState()
    val custom = remember(e.friendWho, stamp) { c.blends.coverFile(e.friendWho).takeIf { it.exists() } }
    val blend = remember(e) { c.blends.blendOf(e) }
    val shape = LocalUi.current.art
    Box(modifier.clip(shape).background(Color(0xFF121017))) {
        if (custom != null) {
            coil3.compose.AsyncImage(
                coil3.request.ImageRequest.Builder(LocalContext.current).data(custom).memoryCacheKey("${custom.path}#$stamp").diskCacheKey("${custom.path}#$stamp").build(),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize(),
            )
        } else {
            val meFirst = blend?.people?.first?.who == c.blends.myWho
            val mineFrom = if (meFirst) BlendSong.FIRST else BlendSong.SECOND
            val theirsFrom = if (meFirst) BlendSong.SECOND else BlendSong.FIRST
            val songs = blend?.songs.orEmpty()
            val mine = (songs.firstOrNull { it.from == mineFrom } ?: songs.firstOrNull { it.from == BlendSong.BOTH })?.let { c.blends.songOf(it.song).thumbnail }
            val theirs = (songs.firstOrNull { it.from == theirsFrom } ?: songs.lastOrNull { it.from == BlendSong.BOTH })?.let { c.blends.songOf(it.song).thumbnail }
            // A soft glow of the split light where the two meet.
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val hues = com.vinz.music.ui.theme.VinzTokens.spectrumColors.map { it.second }
                drawRect(Brush.radialGradient(listOf(hues[hues.size / 2].copy(alpha = 0.30f), hues.first().copy(alpha = 0.10f), Color.Transparent), center = center.copy(y = size.height * 0.46f), radius = size.minDimension * 0.62f))
            }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
                val d = maxWidth * 0.5f
                val y = (maxHeight - d) / 2 - if (small) 0.dp else maxHeight * 0.05f
                val edge = if (small) 1.dp else 1.5.dp
                val me = blend?.people?.let { if (meFirst) it.first else it.second }
                val friend = blend?.people?.let { if (meFirst) it.second else it.first }
                val left = maxWidth * 0.15f
                val right = maxWidth * 0.85f - d
                // Each person's disc: their initial, under their top song's cover once it loads.
                listOf(Triple(left, mine, me?.name), Triple(right, theirs, friend?.name)).forEach { (x, art, name) ->
                    Box(
                        Modifier.padding(start = x, top = y).size(d).clip(CircleShape).background(Color(0xFF26222F)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val initial = name?.trim()?.take(1)?.uppercase().orEmpty()
                        if (initial.isNotEmpty()) Text(initial, color = Color.White.copy(alpha = 0.8f), fontSize = (d.value * 0.38f).sp, fontWeight = FontWeight.Light)
                        else Icon(VinzIcons.Person, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(d * 0.4f))
                        if (art != null) coil3.compose.AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                // Both rings on top, so they cross where the two tastes meet.
                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                    val r = d.toPx() / 2
                    val cy = y.toPx() + r
                    listOf(left.toPx() + r, right.toPx() + r).forEach { cx ->
                        val center = androidx.compose.ui.geometry.Offset(cx, cy)
                        drawCircle(com.vinz.music.ui.theme.VinzTokens.spectrumRing(center), r - edge.toPx() / 2, center, style = androidx.compose.ui.graphics.drawscope.Stroke(edge.toPx()))
                    }
                }
            }
        }
        val match = blend?.match ?: 0
        // Small (a list row): the row's own text says the match.
        if (!small) {
            val scrim = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = if (custom != null) 0.4f else 0f))
            Text(
                "BLEND", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium, letterSpacing = 3.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).then(scrim).padding(horizontal = if (custom != null) 8.dp else 0.dp, vertical = 2.dp),
            )
            Text(
                "$match%", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).then(scrim).padding(horizontal = if (custom != null) 8.dp else 0.dp),
            )
        }
    }
}

/** One Blend: the match, what's shared, the playlist, and more you might both like. */
@Composable
fun BlendScreen(who: String, bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entity by produceState<com.vinz.music.data.db.BlendEntity?>(null, who) { value = c.blends.get(who) }
    val e = entity ?: return
    val blend = remember(e) { c.blends.blendOf(e) } ?: return
    val myWho = c.blends.myWho
    val me = if (blend.people.first.who == myWho) blend.people.first else blend.people.second
    val friend = if (me === blend.people.first) blend.people.second else blend.people.first
    val meFirst = me === blend.people.first
    val songs = remember(blend) { blend.songs.map { c.blends.songOf(it.song) } }
    val title = "${me.name} + ${friend.name}"
    val source = QueueSource(QueueKind.PLAYLIST, "Blend · $title", originId = "blend:$who")
    var menu by remember { mutableStateOf(false) }
    var card by remember { mutableStateOf(false) }
    val discover by produceState(emptyList<com.vinz.music.data.model.Song>(), blend) {
        // More you might both like: radios from songs you both play (or each of your top songs).
        val seeds = (blend.songs.filter { it.from == BlendSong.BOTH } + blend.songs.take(2)).map { it.song.id }.distinct().take(2)
        val inBlend = blend.songs.map { it.song.id }.toSet()
        value = seeds.flatMap { s -> runCatching { withContext(Dispatchers.IO) { c.radio(s, "RDAMVM$s") } }.getOrNull()?.songs.orEmpty() }
            .filter { it.id !in inBlend && !it.isVideo && !c.library.isDisliked(it.id) && !c.exclusions.blocks(it) }
            .distinctBy { it.id }.take(20)
    }

    if (card) BlendCardDialog(blend, me, friend) { card = false }
    // A cover of the listener's own: any photo, from Android's photo picker (no permission asked), cut square.
    val stamp by c.blends.coverStamp.collectAsState()
    val hasOwnCover = remember(who, stamp) { c.blends.coverFile(who).exists() }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            if (!c.blends.setCover(who, uri)) Toast.makeText(context, "Couldn't use that picture", Toast.LENGTH_SHORT).show()
        }
    }

    SubPage(
        title, bottomPadding, subtitle = "${blend.match}% taste match",
        actions = {
            Box {
                RoundAction(VinzIcons.More, "More", glass = true) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Share as a picture") }, { menu = false; card = true }, leadingIcon = { Icon(VinzIcons.Share, null) })
                    DropdownMenuItem({ Text(if (hasOwnCover) "Change the cover" else "Pick a cover") }, {
                        menu = false
                        pickCover.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, leadingIcon = { Icon(VinzIcons.Photo, null) })
                    if (hasOwnCover) DropdownMenuItem({ Text("Use Vinz's cover") }, { menu = false; c.blends.clearCover(who) }, leadingIcon = { Icon(VinzIcons.Blend, null) })
                    DropdownMenuItem({ Text("Send my code back") }, { menu = false; shareCodeText(context, e.myCode, me.name) }, leadingIcon = { Icon(VinzIcons.Groups, null) })
                    if (c.settings.current.isLoggedIn) DropdownMenuItem({ Text("Save to YouTube Music") }, {
                        menu = false
                        scope.launch {
                            val id = runCatching { withContext(Dispatchers.IO) { c.ytm.createPlaylist("Blend · $title", songs.map { it.id }, privacy = "PRIVATE") } }.getOrNull()
                            Toast.makeText(context, if (id != null) "Saved as a private playlist" else "Couldn't save the Blend", Toast.LENGTH_SHORT).show()
                            if (id != null) c.library.refreshCollections()
                        }
                    }, leadingIcon = { Icon(VinzIcons.PlaylistAdd, null) })
                    DropdownMenuItem({ Text("Remove this Blend") }, {
                        menu = false
                        scope.launch { c.blends.remove(who); nav.back() }
                    }, leadingIcon = { Icon(VinzIcons.Delete, null) })
                }
            }
        },
    ) {
        item("top") {
            Column(Modifier.padding(horizontal = 20.dp)) {
                BlendCover(
                    e,
                    Modifier.align(Alignment.CenterHorizontally).widthIn(max = 280.dp).fillMaxWidth(0.72f).aspectRatio(1f)
                        .clickable(onClickLabel = "Pick a cover") { pickCover.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
                Spacer(Modifier.height(16.dp))
                if (blend.sharedArtists.isNotEmpty()) Text(
                    "You both play ${com.vinz.music.data.foryou.MadeForYou.namesLine(blend.sharedArtists)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (blend.sharedGenres.isNotEmpty()) Text(
                    "Shared genres: ${blend.sharedGenres.take(4).joinToString(", ")}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (c.blends.onBothPhones(who)) "On both phones: you and ${friend.name} have the same Blend."
                    else "Send your code back so ${friend.name}'s Blend is the same as yours.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VinzButton({ c.player.playQueue(songs, 0, source) }, Modifier.weight(1f)) { Icon(VinzIcons.Play, null); Spacer(Modifier.width(6.dp)); Text("Play") }
                    VinzOutlinedButton({ c.player.playQueue(songs, 0, source, shuffle = true) }, Modifier.weight(1f)) { Icon(VinzIcons.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle") }
                }
            }
        }
        items(blend.songs.size) { i ->
            val s = blend.songs[i]
            val whose = when (s.from) {
                BlendSong.BOTH -> "Both of you"
                BlendSong.FIRST -> if (meFirst) "You" else friend.name
                else -> if (meFirst) friend.name else "You"
            }
            Row(
                Modifier.fillMaxWidth().clickable { c.player.playQueue(songs, i, source) }.padding(horizontal = 20.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(songs[i].thumbnail, Modifier.size(48.dp), LocalUi.current.art, placeholderIcon = VinzIcons.Song)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    whose, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
        if (discover.isNotEmpty()) item("discover") {
            Column {
                SectionHeader("Discover together", strapline = "New to you both, like what you share")
                LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(discover, key = { it.id }) { s -> ItemCard(SongItem(s), width = 140.dp) { c.player.playQueue(discover, discover.indexOf(s), QueueSource(QueueKind.PLAYLIST, "Discover together")) } }
                }
            }
        }
    }
}

/** A Blend as a picture to share (recorded 1080 px wide). */
@Composable
private fun BlendCardDialog(blend: Blend, me: TasteCode, friend: TasteCode, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            VinzTextButton({
                scope.launch {
                    val ok = shareLayer(context, layer, "vinz-blend.png", "Our Blend on Vinz: ${blend.match}% taste match", "Share your Blend")
                    if (!ok) Toast.makeText(context, "Couldn't make the picture", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            }) { Text("Share") }
        },
        dismissButton = { VinzTextButton(onDismiss) { Text("Cancel") } },
        text = {
            Column(
                Modifier.fillMaxWidth().aspectRatio(9f / 16f).recordInto(layer).clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF2D1B69), Color(0xFF6C5CE7), Color(0xFFE84393))))
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("BLEND", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge, letterSpacing = 4.sp)
                Column {
                    Text(me.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("+ ${friend.name}", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(16.dp))
                    Text("${blend.match}%", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.ExtraLight)
                    Text("taste match", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium)
                }
                Column {
                    if (blend.sharedArtists.isNotEmpty()) {
                        Text("You both play", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelLarge)
                        blend.sharedArtists.take(3).forEach { Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Spacer(Modifier.height(10.dp))
                    }
                    Text("Top of the Blend", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelLarge)
                    blend.songs.take(4).forEach { Text("${it.song.title} · ${it.song.artist}", color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Text("Made with Vinz", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.End))
            }
        },
    )
}

// ---------------------------------------------------------------------------- Scanning

/**
 * Scans a friend's Blend code with the camera (asked for only now, for this). Frames are read on the phone with
 * ZXing; nothing is recorded or sent anywhere.
 */
@Composable
fun BlendScanScreen(
    prompt: String = "Point at your friend's Blend code",
    /** The code in what a QR code says, or null when it holds none (a Blend code by default; Looks pass their own). */
    find: (String?) -> String? = BlendCode::findIn,
    onCode: (String) -> Unit,
) {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) CameraScanner(find, onCode) else Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Vinz needs the camera to scan a code", color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            VinzButton({ ask.launch(Manifest.permission.CAMERA) }) { Text("Allow the camera") }
        }
        Row(Modifier.statusBarsPadding().padding(12.dp)) { RoundAction(VinzIcons.ArrowBack, "Back", glass = true) { nav.back() } }
        Text(
            prompt, color = Color.White, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 18.dp, vertical = 10.dp),
        )
        Box(Modifier.align(Alignment.Center).size(260.dp).border(3.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(24.dp)))
    }
}

@Composable
private fun CameraScanner(find: (String?) -> String?, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val view = remember { PreviewView(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }
    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.getInstance(context).await()
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            // A Blend code is a dense QR code: frames big enough to resolve it.
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val reader = QRCodeReader()
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        analysis.setAnalyzer(executor) { image ->
            try {
                if (done.get()) return@setAnalyzer
                val plane = image.planes[0]
                val stride = plane.rowStride
                val buf = plane.buffer
                val data = ByteArray(stride * image.height)
                buf.get(data, 0, minOf(buf.remaining(), data.size))
                val source = PlanarYUVLuminanceSource(data, stride, image.height, 0, 0, image.width, image.height, false)
                val text = runCatching { reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text }.getOrNull()
                reader.reset()
                val code = find(text)
                // The whole text: besides the code, a QR code shown in Vinz says how to send one back (BlendExchange).
                if (code != null && done.compareAndSet(false, true)) view.post { onCode(text ?: code) }
            } finally {
                image.close()
            }
        }
        provider.unbindAll()
        provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView({ view }, Modifier.fillMaxSize())
}
