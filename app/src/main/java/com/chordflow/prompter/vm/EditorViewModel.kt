package com.chordflow.prompter.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chordflow.prompter.core.ChordParser
import com.chordflow.prompter.core.ChordTransposer
import com.chordflow.prompter.data.SettingsStore
import com.chordflow.prompter.data.Song
import com.chordflow.prompter.data.SongRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Song editor: edit the chart, set the original key, preview the transposition. */
class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SongRepository(app)
    private val settings = SettingsStore(app)

    /** Editor display preferences, persisted in DataStore. */
    data class DisplayState(
        val showChords: Boolean = true,
        val chordsInline: Boolean = false,
        val lyricScale: Float = 1.0f,
        val chordScale: Float = 1.0f
    )

    private val _display = MutableStateFlow(DisplayState())
    val display: StateFlow<DisplayState> = _display.asStateFlow()

    init {
        viewModelScope.launch {
            settings.settings.collect { s ->
                _display.value = DisplayState(
                    showChords = s.editorShowChords,
                    chordsInline = s.chordStyle == SettingsStore.CHORD_STYLE_INLINE,
                    lyricScale = s.fontScale,
                    chordScale = s.chordFontScale
                )
            }
        }
    }

    fun setShowChords(value: Boolean) {
        _display.value = _display.value.copy(showChords = value)
        viewModelScope.launch { settings.setEditorShowChords(value) }
    }

    fun setChordsInline(inline: Boolean) {
        _display.value = _display.value.copy(chordsInline = inline)
        viewModelScope.launch {
            settings.setChordStyle(
                if (inline) SettingsStore.CHORD_STYLE_INLINE else SettingsStore.CHORD_STYLE_ABOVE
            )
        }
    }

    fun setLyricScale(value: Float) {
        _display.value = _display.value.copy(lyricScale = value)
        viewModelScope.launch { settings.setFontScale(value) }
    }

    fun setChordScale(value: Float) {
        _display.value = _display.value.copy(chordScale = value)
        viewModelScope.launch { settings.setChordFontScale(value) }
    }

    data class UiState(
        val loaded: Boolean = false,
        val id: Long = 0,
        val title: String = "",
        val artist: String = "",
        val originalKey: String = "C",
        val transpose: Int = 0,
        val tempo: String = "",
        val content: String = "",
        val setlist: String = Song.DEFAULT_SETLIST,
        val saved: Boolean = false
    ) {
        val soundingKey: String get() = ChordTransposer.transposedKey(originalKey, transpose)
        val transposeLabel: String get() = when {
            transpose > 0 -> "+$transpose"
            transpose < 0 -> "$transpose"
            else -> "0"
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load(id: Long) {
        if (_state.value.loaded && _state.value.id == id) return
        viewModelScope.launch {
            val song = repo.getById(id) ?: return@launch
            _state.value = UiState(
                loaded = true,
                id = song.id,
                title = song.title,
                artist = song.artist,
                originalKey = song.originalKey,
                transpose = song.transposeSemitones,
                tempo = if (song.tempo > 0) song.tempo.toString() else "",
                content = song.content,
                setlist = song.setlist
            )
        }
    }

    fun onTitleChange(v: String) = patch { it.copy(title = v, saved = false) }
    fun onArtistChange(v: String) = patch { it.copy(artist = v, saved = false) }
    fun onTempoChange(v: String) = patch { it.copy(tempo = v.filter { c -> c.isDigit() }.take(3), saved = false) }
    fun onContentChange(v: String) = patch { it.copy(content = v, saved = false) }

    fun setOriginalKey(key: String) = patch { it.copy(originalKey = key, saved = false) }

    fun nudgeTranspose(delta: Int) = patch {
        it.copy(transpose = ChordTransposer.normalizeSemitones(it.transpose + delta), saved = false)
    }

    /** Target Key dropdown: works out the semitone offset for you. */
    fun setTargetKey(targetKey: String) = patch {
        it.copy(
            transpose = ChordTransposer.semitonesBetween(it.originalKey, targetKey),
            saved = false
        )
    }

    fun resetTranspose() = patch { it.copy(transpose = 0, saved = false) }

    /**
     * The parsed chart in the sounding key. The preview draws it with or without
     * chords; the chords shown are always the transposed ones.
     */
    fun previewLines(): List<ChordParser.SongLine> {
        val parsed = ChordParser.parse(_state.value.content)
        return ChordParser.transposed(parsed, _state.value.transpose).lines
    }

    /** The chart as it will sound, chords included, as plain text. */
    fun previewWithChords(): String {
        val parsed = ChordParser.parse(_state.value.content)
        return ChordParser.renderWithChords(parsed, _state.value.transpose)
    }

    /** Exactly what the teleprompter will show: lyrics with every chord stripped. */
    fun previewLyricsOnly(): List<String> {
        val parsed = ChordParser.parse(_state.value.content)
        return parsed.lines.map { if (it.isBlank) "" else it.lyric }
    }

    fun save(onSaved: () -> Unit = {}) {
        val s = _state.value
        if (!s.loaded) return
        viewModelScope.launch {
            val existing = repo.getById(s.id) ?: return@launch
            repo.update(
                existing.copy(
                    title = s.title.ifBlank { "Untitled Song" },
                    artist = s.artist,
                    originalKey = s.originalKey.ifBlank { "C" },
                    transposeSemitones = s.transpose,
                    tempo = s.tempo.toIntOrNull() ?: 0,
                    content = s.content
                )
            )
            _state.value = s.copy(saved = true)
            onSaved()
        }
    }

    private fun patch(block: (UiState) -> UiState) {
        _state.value = block(_state.value)
    }
}
