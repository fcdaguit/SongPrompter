package com.chordflow.prompter.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Song::class, Playlist::class, PlaylistEntry::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 kept one "setlist" name on each song. v2 gives playlists their own tables so a
         * song can be in several. Existing songs are kept; any song that sat in a named
         * setlist is carried over as an entry in a playlist of the same name.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlists` (`name` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`name`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlist_entries` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlist` TEXT NOT NULL, " +
                        "`songId` INTEGER NOT NULL, `position` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_entries_playlist_songId` " +
                        "ON `playlist_entries` (`playlist`, `songId`)"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO playlists (name, createdAt) " +
                        "SELECT DISTINCT setlist, 0 FROM songs WHERE setlist <> 'Library'"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO playlist_entries (playlist, songId, position) " +
                        "SELECT setlist, id, position FROM songs WHERE setlist <> 'Library'"
                )
                db.execSQL("UPDATE songs SET setlist = 'Library'")
            }
        }

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "chordflow.db"
                )
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Seed the two public-domain hymns synchronously as the database
                            // is created, so the library is never empty on first launch.
                            SeedSongs.seeds().forEach { song ->
                                db.execSQL(
                                    """
                                    INSERT INTO songs
                                      (title, artist, originalKey, transposeSemitones, tempo,
                                       content, setlist, position, source, updatedAt)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                    """.trimIndent(),
                                    arrayOf(
                                        song.title,
                                        song.artist,
                                        song.originalKey,
                                        song.transposeSemitones,
                                        song.tempo,
                                        song.content,
                                        song.setlist,
                                        song.position,
                                        song.source,
                                        song.updatedAt
                                    )
                                )
                            }
                        }
                    })
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
