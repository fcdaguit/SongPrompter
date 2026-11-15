package com.chordflow.prompter.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chordflow.prompter.audio.AudioChordDetector
import com.chordflow.prompter.core.ChordMatcher
import com.chordflow.prompter.core.ChordParser
import com.chordflow.prompter.core.ChordTransposer
import com.chordflow.prompter.data.SettingsStore
import com.chordflow.prompter.data.Song
import com.chordflow.prompter.data.SongRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Teleprompter state machine.
 *
 * Each line carries its lyric and its chord triggers. The triggers always drive the
 * detector and move whenever the key changes. Whether they are also drawn on screen is
 * decided by [UiState.showChords] / [UiState.chordsInline], which nothing in the audio
 * or auto-scroll path ever reads.
 */
class TeleprompterViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SongRepository(app)
    private val settings = SettingsStore(app)
    val detector = AudioChordDetector()

    val detectorState: StateFlow<AudioChordDetector.DetectionState> = detector.state

    data class UiState(
        val loaded: Boolean = false,
        val songId: Long = 0,
        val title: String = "",
        val originalKey: String = "C",
        val transpose: Int = 0,
        val lines: List<ChordParser.SongLine> = emptyList(),
        val activeIndex: Int = 0,
        val autoScroll: Boolean = true,
        val listening: Boolean = false,
        val sensitivity: Float = 0.35f,
        val confidence: Float = 0.62f,
        val fontScale: Float = 1.0f,
        val debounceMs: Int = 450,
        val strictness: ChordMatcher.Strictness = ChordMatcher.Strictness.FAMILY,
        val lastMatchedChord: String = "",
        val micError: String? = null,
        // Rendering only — never consulted by onDetection().
        val showChords: Boolean = false,
        val chordsInline: Boolean = false,
        val chordFontScale: Float = 1.0f
    ) {
        val soundingKey: String get() = ChordTransposer.transposedKey(originalKey, transpose)

        /** The chord the engine is waiting to hear next, for the HUD. */
        val nextTriggerChord: String
            get() {
                for (i in (activeIndex + 1) until lines.size) {
                    val c = lines[i].chords.firstOrNull()
                    if (c != null) return c
                }
                return ""
            }

        val transposeLabel: String
            get() = when {
                transpose > 0 -> "+$transpose"
                transpose < 0 -> "$transpose"
                else -> "0"
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var parsedSong: ChordParser.ParsedSong? = null
    private var lastAdvanceAt = 0L
    private var loadedId = -1L
    private var collectorsStarted = false

    fun load(songId: Long) {
        if (loadedId == songId && _state.value.loaded) return
        loadedId = songId
        viewModelScope.launch {
            val song: Song = repo.getById(songId) ?: return@launch
            val parsed = ChordParser.parse(song.content)
            parsedSong = parsed
            val s = settings.settings.first()
            applyLoaded(song, parsed, s)
        }
        if (collectorsStarted) return
        collectorsStarted = true

        // Settings arrive on their own flow so later changes are picked up too.
        viewModelScope.launch {
            settings.settings.collect { s ->
                detector.sensitivity = s.sensitivity
                detector.confidenceThreshold = s.confidence
                _state.value = _state.value.copy(
                    sensitivity = s.sensitivity,
                    confidence = s.confidence,
                    fontScale = s.fontScale,
                    debounceMs = s.debounceMs,
                    autoScroll = s.autoScrollEnabled,
                    strictness = parseStrictness(s.matchStrictness),
                    showChords = s.teleprompterShowChords,
                    chordsInline = s.chordStyle == SettingsStore.CHORD_STYLE_INLINE,
                    chordFontScale = s.chordFontScale
                )
            }
        }
        viewModelScope.launch {
            detector.state.collect { d -> onDetection(d) }
        }
    }

    private fun applyLoaded(song: Song, parsed: ChordParser.ParsedSong, s: SettingsStore.Settings?) {
        val transpose = song.transposeSemitones
        _state.value = _state.value.copy(
            loaded = true,
            songId = song.id,
            title = song.title,
            originalKey = song.originalKey.ifBlank { parsed.key },
            transpose = transpose,
            lines = ChordParser.transposed(parsed, transpose).lines,
            activeIndex = firstLyricIndex(parsed.lines),
            sensitivity = s?.sensitivity ?: 0.35f,
            confidence = s?.confidence ?: 0.62f,
            fontScale = s?.fontScale ?: 1.0f,
            debounceMs = s?.debounceMs ?: 450,
            autoScroll = s?.autoScrollEnabled ?: true,
            strictness = parseStrictness(s?.matchStrictness ?: "FAMILY"),
            showChords = s?.teleprompterShowChords ?: false,
            chordsInline = s?.chordStyle == SettingsStore.CHORD_STYLE_INLINE,
            chordFontScale = s?.chordFontScale ?: 1.0f
        )
    }

    private fun firstLyricIndex(lines: List<ChordParser.SongLine>): Int {
        val i = lines.indexOfFirst { !it.isBlank && it.lyric.isNotBlank() }
        return if (i >= 0) i else 0
    }

    private fun parseStrictness(value: String): ChordMatcher.Strictness = when (value.uppercase()) {
        "ROOT" -> ChordMatcher.Strictness.ROOT
        "EXACT" -> ChordMatcher.Strictness.EXACT
        else -> ChordMatcher.Strictness.FAMILY
    }

    // ---------------------------------------------------------------- audio

    fun startListening() {
        val st = _state.value
        if (st.listening) return
        detector.sensitivity = st.sensitivity
        detector.confidenceThreshold = st.confidence
        detector.start()
        _state.value = st.copy(listening = true, micError = null)
    }

    fun stopListening() {
        detector.stop()
        _state.value = _state.value.copy(listening = false)
    }

    fun toggleListening() {
        if (_state.value.listening) stopListening() else startListening()
    }

    private fun onDetection(d: AudioChordDetector.DetectionState) {
        if (d.error != null) {
            _state.value = _state.value.copy(micError = d.error, listening = false)
            return
        }
        val st = _state.value
        if (!st.autoScroll || !st.listening || d.chord.isBlank() || d.gated) return

        val now = System.currentTimeMillis()
        if (now - lastAdvanceAt < st.debounceMs) return

        // Look ahead for the next line that carries a chord trigger.
        var target = -1
        for (i in (st.activeIndex + 1) until st.lines.size) {
            if (st.lines[i].chords.isNotEmpty()) {
                target = i
                break
            }
        }
        if (target < 0) return

        if (ChordMatcher.matchesAny(d.chord, st.lines[target].chords, st.strictness)) {
            lastAdvanceAt = now
            _state.value = st.copy(activeIndex = target, lastMatchedChord = d.chord)
        }
    }

    // ------------------------------------------------------------- controls

    fun setActiveIndex(index: Int) {
        val clamped = index.coerceIn(0, (_state.value.lines.size - 1).coerceAtLeast(0))
        lastAdvanceAt = System.currentTimeMillis()
        _state.value = _state.value.copy(activeIndex = clamped)
    }

    fun stepLine(delta: Int) {
        val st = _state.value
        var i = st.activeIndex + delta
        // Skip blank spacer lines so a tap always lands on readable text.
        while (i in st.lines.indices && st.lines[i].isBlank) i += delta
        setActiveIndex(i)
    }

    fun restart() = setActiveIndex(firstLyricIndex(_state.value.lines))

    /** Live HUD key change — moves the triggers (and any chords on screen), never the lyrics. */
    fun nudgeTranspose(delta: Int) {
        val st = _state.value
        val parsed = parsedSong ?: return
        val next = ChordTransposer.normalizeSemitones(st.transpose + delta)
        _state.value = st.copy(
            transpose = next,
            lines = ChordParser.transposed(parsed, next).lines
        )
        viewModelScope.launch { repo.setTranspose(st.songId, next) }
    }

    fun setTargetKey(key: String) {
        val st = _state.value
        val parsed = parsedSong ?: return
        val next = ChordTransposer.semitonesBetween(st.originalKey, key)
        _state.value = st.copy(
            transpose = next,
            lines = ChordParser.transposed(parsed, next).lines
        )
        viewModelScope.launch { repo.setTranspose(st.songId, next) }
    }

    fun setSensitivity(value: Float) {
        detector.sensitivity = value
        _state.value = _state.value.copy(sensitivity = value)
        viewModelScope.launch { settings.setSensitivity(value) }
    }

    fun setConfidence(value: Float) {
        detector.confidenceThreshold = value
        _state.value = _state.value.copy(confidence = value)
        viewModelScope.launch { settings.setConfidence(value) }
    }

    fun setFontScale(value: Float) {
        _state.value = _state.value.copy(fontScale = value)
        viewModelScope.launch { settings.setFontScale(value) }
    }

    // ---- display mode: state + DataStore only, the song is not reloaded ----

    fun toggleShowChords() = setShowChords(!_state.value.showChords)

    fun setShowChords(value: Boolean) {
        _state.value = _state.value.copy(showChords = value)
        viewModelScope.launch { settings.setTeleprompterShowChords(value) }
    }

    /** Mode A = lyrics only, Mode B = chords above, Mode C = inline. */
    fun setLayout(showChords: Boolean, inline: Boolean) {
        val st = _state.value
        _state.value = st.copy(showChords = showChords, chordsInline = if (showChords) inline else st.chordsInline)
        viewModelScope.launch {
            settings.setTeleprompterShowChords(showChords)
            if (showChords) {
                settings.setChordStyle(
                    if (inline) SettingsStore.CHORD_STYLE_INLINE else SettingsStore.CHORD_STYLE_ABOVE
                )
            }
        }
    }

    fun setChordFontScale(value: Float) {
        _state.value = _state.value.copy(chordFontScale = value)
        viewModelScope.launch { settings.setChordFontScale(value) }
    }

    fun toggleAutoScroll() {
        val next = !_state.value.autoScroll
        _state.value = _state.value.copy(autoScroll = next)
        viewModelScope.launch { settings.setAutoScroll(next) }
    }

    fun cycleStrictness() {
        val next = when (_state.value.strictness) {
            ChordMatcher.Strictness.ROOT -> ChordMatcher.Strictness.FAMILY
            ChordMatcher.Strictness.FAMILY -> ChordMatcher.Strictness.EXACT
            ChordMatcher.Strictness.EXACT -> ChordMatcher.Strictness.ROOT
        }
        _state.value = _state.value.copy(strictness = next)
        viewModelScope.launch { settings.setStrictness(next.name) }
    }

    override fun onCleared() {
        super.onCleared()
        detector.stop()
    }
}
