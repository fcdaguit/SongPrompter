package com.chordflow.prompter.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A named playlist. Every song lives in the Library; a playlist is an ordered selection
 * of Library songs, so one song can sit in several playlists without being copied.
 */
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** One song's place in one playlist. */
@Entity(
    tableName = "playlist_entries",
    indices = [Index(value = ["playlist", "songId"], unique = true)]
)
data class PlaylistEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlist: String,
    val songId: Long,
    /** Sort order inside the playlist; reordering rewrites this. */
    val position: Int = 0
)
