package com.chordflow.prompter.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One chord sheet in the offline library.
 *
 * [content] keeps the original ChordPro / chord-over-lyric text exactly as downloaded or
 * imported, so the parser can re-derive lyrics and chord triggers at any time and the
 * user can always edit the real source.
 */
@Entity(tableName = "songs")
data class Song(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String = "",
    val originalKey: String = "C",
    /** Saved per-song key change, -11..+11. */
    val transposeSemitones: Int = 0,
    val tempo: Int = 0,
    val content: String,
    /**
     * Legacy column from before playlists had their own tables. Always [LIBRARY] now;
     * kept so existing installs upgrade without rebuilding the songs table.
     */
    val setlist: String = DEFAULT_SETLIST,
    /** Sort order inside the Library; reordering rewrites this. */
    val position: Int = 0,
    val source: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** The built-in list holding every song. Not a playlist, and cannot be deleted. */
        const val LIBRARY = "Library"
        const val DEFAULT_SETLIST = LIBRARY
    }
}
