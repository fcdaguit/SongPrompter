package com.chordflow.prompter.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chordflow.prompter.data.PlaylistEntry
import com.chordflow.prompter.data.SettingsStore
import com.chordflow.prompter.data.Song
import com.chordflow.prompter.data.SongRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Library and playlist manager state.
 *
 * The Library holds every offline song. A playlist is an ordered pick of Library songs;
 * adding a song to a playlist never copies or moves it.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SongRepository(app)
    private val settings = SettingsStore(app)

    /** Every offline song, in Library order. */
    val songs: StateFlow<List<Song>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Playlist names (the Library is not one of them). */
    val playlists: StateFlow<List<String>> = repo.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Which song sits where in which playlist. */
    val entries: StateFlow<List<PlaylistEntry>> = repo.observeEntries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** [Song.LIBRARY] or the name of the playlist being viewed. */
    private val _activePlaylist = MutableStateFlow(Song.LIBRARY)
    val activePlaylist: StateFlow<String> = _activePlaylist.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // Reopen the playlist that was showing last time, if it still exists.
        viewModelScope.launch {
            val last = settings.settings.first().lastSetlist
            if (last != Song.LIBRARY && last in repo.playlistNames() &&
                _activePlaylist.value == Song.LIBRARY
            ) {
                _activePlaylist.value = last
            }
        }
    }

    fun selectPlaylist(name: String) {
        _activePlaylist.value = name
        viewModelScope.launch { settings.setLastSetlist(name) }
    }

    fun createPlaylist(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        if (clean.equals(Song.LIBRARY, ignoreCase = true)) {
            selectPlaylist(Song.LIBRARY)
            return
        }
        viewModelScope.launch {
            repo.createPlaylist(clean)
            selectPlaylist(clean)
            _message.value = "Playlist \"$clean\" ready. Add songs from any song's menu, or from the teleprompter."
        }
    }

    /** Removes the playlist; its songs stay in the Library. */
    fun deletePlaylist(name: String) {
        if (name == Song.LIBRARY) return
        viewModelScope.launch {
            repo.deletePlaylist(name)
            if (_activePlaylist.value == name) selectPlaylist(Song.LIBRARY)
            _message.value = "Deleted playlist \"$name\". Its songs are still in the Library."
        }
    }

    fun addToPlaylist(song: Song, playlist: String) {
        viewModelScope.launch {
            val added = repo.addToPlaylist(song.id, playlist)
            _message.value = if (added) "Added \"${song.title}\" to \"${playlist.trim()}\"."
            else "\"${song.title}\" is already in \"${playlist.trim()}\"."
        }
    }

    fun removeFromPlaylist(song: Song, playlist: String) {
        viewModelScope.launch {
            repo.removeFromPlaylist(song.id, playlist)
            _message.value = "Removed \"${song.title}\" from \"$playlist\". It is still in the Library."
        }
    }

    fun importFile(uri: Uri) {
        viewModelScope.launch {
            val target = _activePlaylist.value
            val result = repo.importFromUri(uri, target)
            _message.value = result.fold(
                onSuccess = {
                    if (target == Song.LIBRARY) "Imported into the Library."
                    else "Imported into the Library and \"$target\"."
                },
                onFailure = { "Import failed: ${it.message ?: "unreadable file"}" }
            )
        }
    }

    fun createBlankSong(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repo.addFromText(
                text = "{title: New Song}\n{key: G}\n\n[G]Type your lyrics [D]here\n",
                playlist = _activePlaylist.value,
                fallbackTitle = "New Song",
                source = "Created in app"
            )
            onCreated(id)
        }
    }

    /** Deletes the song everywhere: the Library and every playlist. */
    fun delete(song: Song) {
        viewModelScope.launch {
            repo.delete(song)
            _message.value = "Deleted \"${song.title}\"."
        }
    }

    /** Persist a new order for the list being viewed (the Library or one playlist). */
    fun applyOrder(ordered: List<Song>) {
        val listName = _activePlaylist.value
        viewModelScope.launch { repo.applyOrder(listName, ordered.map { it.id }) }
    }

    fun nudgeTranspose(song: Song, delta: Int) {
        viewModelScope.launch {
            val next = com.chordflow.prompter.core.ChordTransposer.normalizeSemitones(
                song.transposeSemitones + delta
            )
            repo.setTranspose(song.id, next)
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
