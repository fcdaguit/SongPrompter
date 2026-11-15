package com.chordflow.prompter.core

/**
 * Parses the three chord-sheet formats worship leaders actually have on their phones:
 *
 *  1. ChordPro directives      {title: Amazing Grace} {key: G} {artist: ...} {tempo: 84}
 *  2. Inline bracketed chords  [G]Amazing [D]grace, how [Em]sweet the [C]sound
 *  3. Chord-over-lyric text    G        D        Em       C
 *                              Amazing grace, how sweet the sound
 *
 * [SongLine.chords] carries the triggers the audio engine matches against. That list is
 * the single source of truth for detection and auto-scroll and is identical in every
 * display mode. [SongLine.positions] only says where each chord sits over the lyric, so
 * the screen can draw chords when the user turns them on. Rendering never feeds back
 * into matching.
 */
object ChordParser {

    data class SongLine(
        val lyric: String,
        val chords: List<String>,
        val isSection: Boolean = false,
        val isBlank: Boolean = false,
        /** Character offset into [lyric] for each entry of [chords]. Display only. */
        val positions: List<Int> = emptyList()
    )

    /** A run of lyric text with the chord that lands on its first character ("" = none). */
    data class Segment(val chord: String, val text: String)

    data class ParsedSong(
        val title: String,
        val artist: String,
        val key: String,
        val tempo: Int,
        val lines: List<SongLine>
    ) {
        /** All chords in order of appearance, de-duplicated consecutively. */
        val chordProgression: List<String>
            get() {
                val out = mutableListOf<String>()
                lines.forEach { line ->
                    line.chords.forEach { c -> if (out.lastOrNull() != c) out.add(c) }
                }
                return out
            }
    }

    private val DIRECTIVE = Regex("^\\s*\\{\\s*([a-zA-Z_]+)\\s*:?\\s*(.*?)\\s*}\\s*$")
    private val INLINE_CHORD = Regex("\\[([^\\]]{1,12})]")
    private val TOKEN = Regex("\\S+")
    private val SECTION_HINT = Regex(
        "^\\s*\\(?\\s*(verse|chorus|bridge|intro|outro|tag|pre-?chorus|refrain|interlude|instrumental|ending|coda|vamp|turnaround)\\b.*",
        RegexOption.IGNORE_CASE
    )

    fun parse(raw: String): ParsedSong {
        var title = ""
        var artist = ""
        var key = ""
        var tempo = 0

        val sourceLines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val staged = mutableListOf<SongLine>()

        var i = 0
        while (i < sourceLines.size) {
            val line = sourceLines[i]

            // --- 1. ChordPro directive ---------------------------------------
            val directive = DIRECTIVE.find(line)
            if (directive != null) {
                val name = directive.groupValues[1].lowercase()
                val value = directive.groupValues[2].trim()
                when (name) {
                    "title", "t" -> if (title.isEmpty()) title = value
                    "subtitle", "st", "artist", "composer" -> if (artist.isEmpty()) artist = value
                    "key" -> if (key.isEmpty()) key = value
                    "tempo", "bpm" -> tempo = value.filter { it.isDigit() }.toIntOrNull() ?: tempo
                    "comment", "c", "comment_italic", "ci" ->
                        if (value.isNotEmpty()) staged.add(SongLine(value, emptyList(), isSection = true))
                    "start_of_chorus", "soc" -> staged.add(SongLine("CHORUS", emptyList(), isSection = true))
                    "start_of_verse", "sov" -> staged.add(SongLine("VERSE", emptyList(), isSection = true))
                    "start_of_bridge", "sob" -> staged.add(SongLine("BRIDGE", emptyList(), isSection = true))
                    // end_of_* and anything unknown: drop silently.
                }
                i++
                continue
            }

            // --- 2. Blank line -----------------------------------------------
            if (line.isBlank()) {
                if (staged.isNotEmpty() && !staged.last().isBlank) {
                    staged.add(SongLine("", emptyList(), isBlank = true))
                }
                i++
                continue
            }

            // --- 3. Inline bracketed chords ----------------------------------
            if (INLINE_CHORD.containsMatchIn(line)) {
                val chords = mutableListOf<String>()
                val rawPositions = mutableListOf<Int>()
                val stripped = StringBuilder()
                var last = 0
                for (m in INLINE_CHORD.findAll(line)) {
                    stripped.append(line.substring(last, m.range.first))
                    val token = m.groupValues[1].trim()
                    if (ChordTransposer.isChordToken(token)) {
                        chords.add(token)
                        rawPositions.add(stripped.length)
                    }
                    last = m.range.last + 1
                }
                stripped.append(line.substring(last))
                val rawLyric = stripped.toString()
                val lead = rawLyric.length - rawLyric.trimStart().length
                val lyric = rawLyric.trim()
                if (lyric.isEmpty() && chords.isNotEmpty()) {
                    // A pure chord line written in ChordPro brackets — e.g. "[G] [D] [Em] [C]"
                    staged.add(SongLine("", chords, isBlank = true))
                } else {
                    val positions = rawPositions.map { (it - lead).coerceIn(0, lyric.length) }
                    staged.add(
                        SongLine(lyric, chords, isSection = isSectionLabel(lyric), positions = positions)
                    )
                }
                i++
                continue
            }

            // --- 4. Chord-over-lyric -----------------------------------------
            if (isChordLine(line)) {
                val chordTokens = TOKEN.findAll(line)
                    .filter { ChordTransposer.isChordToken(it.value) }
                    .toList()
                val chords = chordTokens.map { it.value }
                // Attach the chords to the next non-blank, non-chord line.
                var j = i + 1
                while (j < sourceLines.size && sourceLines[j].isBlank()) j++
                if (j < sourceLines.size && !isChordLine(sourceLines[j]) &&
                    !DIRECTIVE.matches(sourceLines[j])
                ) {
                    val lyricSource = sourceLines[j]
                    val lead = lyricSource.length - lyricSource.trimStart().length
                    val lyric = lyricSource.trim()
                    // The chord's column in the chord line is its place over the lyric.
                    val positions = chordTokens.map { (it.range.first - lead).coerceIn(0, lyric.length) }
                    staged.add(
                        SongLine(lyric, chords, isSection = isSectionLabel(lyric), positions = positions)
                    )
                    i = j + 1
                } else {
                    // Instrumental run with no lyric underneath.
                    staged.add(SongLine("", chords, isBlank = true))
                    i++
                }
                continue
            }

            // --- 5. Plain lyric ----------------------------------------------
            val text = line.trim()
            staged.add(SongLine(text, emptyList(), isSection = isSectionLabel(text)))
            i++
        }

        // Trim leading/trailing blanks.
        while (staged.isNotEmpty() && staged.first().isBlank) staged.removeAt(0)
        while (staged.isNotEmpty() && staged.last().isBlank) staged.removeAt(staged.size - 1)

        if (title.isEmpty()) {
            title = staged.firstOrNull { it.lyric.isNotBlank() && !it.isSection }?.lyric?.take(60) ?: "Untitled"
        }
        if (key.isEmpty()) {
            // Auto-detect from the first chord in the song.
            val first = staged.firstOrNull { it.chords.isNotEmpty() }?.chords?.firstOrNull()
            if (first != null) {
                val parsed = ChordTransposer.parse(first)
                if (parsed != null) {
                    val name = ChordTransposer.pitchClassToName(parsed.root, useFlats = false)
                    key = if (parsed.isMinor) name + "m" else name
                }
            }
        }
        if (key.isEmpty()) key = "C"

        return ParsedSong(title, artist, key, tempo, staged)
    }

    private fun isSectionLabel(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.startsWith("[") && t.endsWith("]")) return true
        if (t.endsWith(":") && t.length <= 24) return true
        return SECTION_HINT.matches(t) && t.length <= 32
    }

    /**
     * A line is a chord line when it holds at least one chord and every
     * whitespace-separated token is a chord, a bar line, or a rhythm mark.
     */
    fun isChordLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        val tokens = trimmed.split(Regex("\\s+"))
        if (tokens.size > 24) return false
        var chordCount = 0
        for (t in tokens) {
            if (t == "|" || t == "||" || t == "/" || t == "%" || t == "-" || t == "x2" || t == "x3" || t == "x4") continue
            if (ChordTransposer.isChordToken(t)) chordCount++ else return false
        }
        return chordCount > 0
    }

    /**
     * Returns the song's lines with every chord transposed by [semitones].
     * The lyric text and chord positions are untouched — only the chord names move, so
     * the same list drives both the detector and whatever chords are on screen.
     */
    fun transposed(song: ParsedSong, semitones: Int): ParsedSong {
        if (semitones == 0) return song
        val useFlats = ChordTransposer.useFlatsForKey(song.key, semitones)
        val moved = song.lines.map { line ->
            line.copy(chords = line.chords.map { ChordTransposer.transposeChord(it, semitones, useFlats) })
        }
        return song.copy(lines = moved)
    }

    /**
     * Splits a line into lyric runs, each starting at a chord. Display only.
     * "[G]Amazing [D]grace" -> (G,"Amazing "), (D,"grace").
     */
    fun segments(line: SongLine): List<Segment> {
        if (line.chords.isEmpty()) return listOf(Segment("", line.lyric))
        if (line.positions.size != line.chords.size) {
            // No placement information: stack the chords at the start of the line.
            val out = line.chords.dropLast(1).map { Segment(it, "") }.toMutableList()
            out.add(Segment(line.chords.last(), line.lyric))
            return out
        }
        val placed = line.chords.zip(line.positions.map { it.coerceIn(0, line.lyric.length) })
            .sortedBy { it.second }
        val out = mutableListOf<Segment>()
        if (placed.first().second > 0) {
            out.add(Segment("", line.lyric.substring(0, placed.first().second)))
        }
        for (k in placed.indices) {
            val start = placed[k].second
            val end = if (k + 1 < placed.size) placed[k + 1].second else line.lyric.length
            out.add(Segment(placed[k].first, line.lyric.substring(start, end)))
        }
        return out
    }

    /**
     * [segments] regrouped into words, so the chords-above layout can wrap between
     * words without ever splitting one. Each inner list is one unbreakable word.
     */
    fun words(line: SongLine): List<List<Segment>> {
        val out = mutableListOf<List<Segment>>()
        var current = mutableListOf<Segment>()
        for (seg in segments(line)) {
            var chord = seg.chord
            val buf = StringBuilder()
            for (ch in seg.text) {
                if (ch.isWhitespace()) {
                    if (buf.isNotEmpty() || chord.isNotEmpty()) {
                        current.add(Segment(chord, buf.toString()))
                        chord = ""
                        buf.setLength(0)
                    }
                    if (current.isNotEmpty()) {
                        out.add(current)
                        current = mutableListOf()
                    }
                } else {
                    buf.append(ch)
                }
            }
            if (buf.isNotEmpty() || chord.isNotEmpty()) {
                current.add(Segment(chord, buf.toString()))
            }
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    /**
     * Re-render the chart as readable ChordPro-style text with the chords transposed.
     * Plain-text export of the chart; the screens draw from [segments] instead.
     */
    fun renderWithChords(song: ParsedSong, semitones: Int): String {
        val moved = transposed(song, semitones)
        val sb = StringBuilder()
        moved.lines.forEach { line ->
            when {
                line.isBlank && line.chords.isEmpty() -> sb.append('\n')
                line.isBlank -> sb.append(line.chords.joinToString("  ")).append('\n')
                line.chords.isEmpty() -> sb.append(line.lyric).append('\n')
                else -> {
                    sb.append(line.chords.joinToString("  ")).append('\n')
                    sb.append(line.lyric).append('\n')
                }
            }
        }
        return sb.toString().trimEnd()
    }
}
