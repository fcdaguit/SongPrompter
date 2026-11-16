package com.chordflow.prompter.data

import android.content.Context
import android.net.Uri
import com.chordflow.prompter.core.ChordParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/** Single entry point for everything the app stores locally. 100% offline. */
class SongRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).songDao()
    private val playlistDao = AppDatabase.get(context).playlistDao()

    fun observeAll(): Flow<List<Song>> = dao.observeAll()
    fun observeSong(id: Long): Flow<Song?> = dao.observeById(id)

    // ---- playlists -------------------------------------------------------------

    /** Playlist names, alphabetical. The Library is not a playlist and is not listed. */
    fun observePlaylists(): Flow<List<String>> = playlistDao.observePlaylistNames()

    /** Every (playlist, song, position) row, ordered by playlist then position. */
    fun observeEntries(): Flow<List<PlaylistEntry>> = playlistDao.observeEntries()

    suspend fun playlistNames(): List<String> = playlistDao.playlistNames()

    private fun isLibrary(name: String) = name.trim().equals(Song.LIBRARY, ignoreCase = true)

    suspend fun createPlaylist(name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || isLibrary(clean)) return
        playlistDao.insertPlaylist(Playlist(name = clean))
    }

    /**
     * Puts a Library song at the end of a playlist, creating the playlist if needed.
     * Returns false when the song was already there.
     */
    suspend fun addToPlaylist(songId: Long, playlist: String): Boolean {
        val clean = playlist.trim()
        if (clean.isEmpty() || isLibrary(clean)) return false
        playlistDao.insertPlaylist(Playlist(name = clean))
        val position = playlistDao.nextPosition(clean)
        return playlistDao.insertEntry(
            PlaylistEntry(playlist = clean, songId = songId, position = position)
        ) != -1L
    }

    suspend fun removeFromPlaylist(songId: Long, playlist: String) =
        playlistDao.removeEntry(playlist, songId)

    /** Deletes the playlist only. Its songs stay in the Library. */
    suspend fun deletePlaylist(name: String) {
        playlistDao.clearPlaylist(name)
        playlistDao.deletePlaylist(name)
    }

    suspend fun getById(id: Long): Song? = dao.getById(id)

    /** Create a song from raw ChordPro / chord-over-lyric text, filling metadata from the parse. */
    suspend fun addFromText(
        text: String,
        playlist: String = Song.LIBRARY,
        fallbackTitle: String = "",
        source: String = ""
    ): Long {
        val parsed = ChordParser.parse(text)
        val title = parsed.title.ifBlank { fallbackTitle.ifBlank { "Untitled Song" } }
        val position = dao.nextPosition()
        val id = dao.insert(
            Song(
                title = title,
                artist = parsed.artist,
                originalKey = parsed.key,
                tempo = parsed.tempo,
                content = text,
                position = position,
                source = source
            )
        )
        // The song always lands in the Library; it also joins the playlist being viewed.
        if (!isLibrary(playlist)) addToPlaylist(id, playlist)
        return id
    }

    suspend fun update(song: Song) = dao.update(song.copy(updatedAt = System.currentTimeMillis()))

    /** Deletes the song from the Library and from every playlist it was in. */
    suspend fun delete(song: Song) {
        playlistDao.removeSongEverywhere(song.id)
        dao.delete(song)
    }

    suspend fun setTranspose(id: Long, semitones: Int) =
        dao.setTranspose(id, semitones, System.currentTimeMillis())

    /** Rewrites positions after a reorder — of the Library, or of one playlist. */
    suspend fun applyOrder(listName: String, ids: List<Long>) {
        if (isLibrary(listName)) {
            ids.forEachIndexed { index, id -> dao.setPosition(id, index) }
        } else {
            ids.forEachIndexed { index, id -> playlistDao.setPosition(listName, id, index) }
        }
    }

    /**
     * Import a local .cho / .onsong / .pro / .txt file picked with the storage picker.
     * Reads through the content resolver, so Drive and Downloads both work.
     */
    suspend fun importFromUri(uri: Uri, playlist: String): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            } ?: return@withContext Result.failure(IllegalStateException("Could not open that file"))

            if (text.isBlank()) {
                return@withContext Result.failure(IllegalStateException("That file is empty"))
            }
            val name = displayName(uri)
            val id = addFromText(
                text = text,
                playlist = playlist,
                fallbackTitle = name.substringBeforeLast('.'),
                source = "Imported from device: $name"
            )
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "Imported song"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)?.let { name = it }
                }
            }
        } catch (_: Exception) {
            // Keep the path-derived name.
        }
        return name.substringAfterLast('/')
    }
}
