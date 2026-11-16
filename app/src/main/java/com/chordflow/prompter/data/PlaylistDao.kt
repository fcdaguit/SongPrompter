package com.chordflow.prompter.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT name FROM playlists ORDER BY name COLLATE NOCASE ASC")
    fun observePlaylistNames(): Flow<List<String>>

    @Query("SELECT name FROM playlists ORDER BY name COLLATE NOCASE ASC")
    suspend fun playlistNames(): List<String>

    @Query("SELECT * FROM playlist_entries ORDER BY playlist ASC, position ASC, id ASC")
    fun observeEntries(): Flow<List<PlaylistEntry>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    /** Returns -1 when the song is already in that playlist. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntry(entry: PlaylistEntry): Long

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_entries WHERE playlist = :playlist")
    suspend fun nextPosition(playlist: String): Int

    @Query("UPDATE playlist_entries SET position = :position WHERE playlist = :playlist AND songId = :songId")
    suspend fun setPosition(playlist: String, songId: Long, position: Int)

    @Query("DELETE FROM playlist_entries WHERE playlist = :playlist AND songId = :songId")
    suspend fun removeEntry(playlist: String, songId: Long)

    @Query("DELETE FROM playlist_entries WHERE songId = :songId")
    suspend fun removeSongEverywhere(songId: Long)

    @Query("DELETE FROM playlist_entries WHERE playlist = :playlist")
    suspend fun clearPlaylist(playlist: String)

    @Query("DELETE FROM playlists WHERE name = :name")
    suspend fun deletePlaylist(name: String)
}
