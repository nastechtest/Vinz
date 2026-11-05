from pathlib import Path


def replace(path, old, new):
    p = Path(path)
    text = p.read_text()
    if text.count(old) != 1:
        raise SystemExit(f"Expected exactly one integration anchor in {path}: {old[:90]}")
    p.write_text(text.replace(old, new))


prefs = "app/src/main/java/com/vinz/music/data/prefs/Settings.kt"
replace(prefs, "    val showPlayingFrom: Boolean = true,", "    val showPlayingFrom: Boolean = true,\n    /** Optional next-track card; existing installs keep their player unchanged. */\n    val showNextTrack: Boolean = false,".replace("\n", "\n"))
replace(prefs, "        val showPlayingFrom = booleanPreferencesKey(\"show_playing_from\")", "        val showPlayingFrom = booleanPreferencesKey(\"show_playing_from\")\n        val showNextTrack = booleanPreferencesKey(\"show_next_track\")")
replace(prefs, "            showPlayingFrom = p[K.showPlayingFrom] ?: d.showPlayingFrom,", "            showPlayingFrom = p[K.showPlayingFrom] ?: d.showPlayingFrom,\n            showNextTrack = p[K.showNextTrack] ?: d.showNextTrack,")
replace(prefs, "    fun setShowPlayingFrom(v: Boolean) = edit { it[K.showPlayingFrom] = v }", "    fun setShowPlayingFrom(v: Boolean) = edit { it[K.showPlayingFrom] = v }\n    fun setShowNextTrack(v: Boolean) = edit { it[K.showNextTrack] = v }")
replace(prefs, "                playerTimes, meshSpeed, meshIntensity, playerBlur, canvasFillsBackground,", "                playerTimes, meshSpeed, meshIntensity, playerBlur, canvasFillsBackground, showNextTrack,")
old = "                showPlayingFrom to " + chr(39) + "b" + chr(39) + ", playerShuffleRepeat"
new = "                showNextTrack to " + chr(39) + "b" + chr(39) + ", showPlayingFrom to " + chr(39) + "b" + chr(39) + ", playerShuffleRepeat"
replace(prefs, old, new)

connection = "app/src/main/java/com/vinz/music/playback/PlayerConnection.kt"
replace(connection, "    val upNext: StateFlow<List<Int>> = _upNext", "    val upNext: StateFlow<List<Int>> = _upNext\n    private val _nextPreviewSong = MutableStateFlow<Song?>(null)\n    /** Natural playback target, including repeat and shuffle; presentation only. */\n    val nextPreviewSong: StateFlow<Song?> = _nextPreviewSong")
replace(connection, "        // Before the current song in play order, oldest first (#68: the queue only went forward).", "        val nextPreviewIndex = nextPreviewWindow(p.currentTimeline, p.currentMediaItemIndex, p.repeatMode, p.shuffleModeEnabled)\n        _nextPreviewSong.value = _queue.value.firstOrNull { it.index == nextPreviewIndex }?.song\n        // Before the current song in play order, oldest first (#68: the queue only went forward).")

player = "app/src/main/java/com/vinz/music/ui/player/NowPlaying.kt"
anchor = "                        val deckGap = if (roomyCover) 12.dp else 18.dp"
replace(player, anchor, anchor + "\n                        if (settings.showNextTrack && !lyricsOpen && !queueOpen && !statsOpen && !covered) {\n                            NextTrackPreview(\n                                onOpenQueue = { if (!queueOpen) toggleQueue() },\n                                modifier = Modifier.padding(bottom = 8.dp),\n                            )\n                        }")

your_way = "app/src/main/java/com/vinz/music/ui/screens/PlayerYourWay.kt"
anchor = "        // The parts under the title, top to bottom, as they sit on the player."
replace(your_way, anchor, "        Toggle(tr(R.string.show_next_track_preview), tr(R.string.show_next_track_preview_detail), s.showNextTrack) { prefs.setShowNextTrack(it) }\n" + anchor)

strings = "app/src/main/res/values/strings.xml"
replace(strings, "</resources>", "    <string name=\"show_next_track_preview\">Show next track</string>\n    <string name=\"show_next_track_preview_detail\">A compact preview in the portrait player. Follows shuffle and repeat; tap to open the queue.</string>\n    <string name=\"next_track_preview_label\">Next track</string>\n    <string name=\"next_track_preview_open_queue\">Open queue</string>\n</resources>")

# Convert escaped newlines/quotes in replacement payloads only. These files had no such
# artifacts before this script; fail if expected markers do not become valid source.
for name in [prefs, connection, player, your_way, strings]:
    p = Path(name)
    t = p.read_text()
    # Replacement strings above are ordinary Python strings after source parsing.
    # A literal backslash-n is not part of any inserted Kotlin value.
    if "\\n    /** Optional next-track" in t:
        raise SystemExit("Invalid generated newline")
print("Integrated optional next-track preview without changing existing defaults.")
