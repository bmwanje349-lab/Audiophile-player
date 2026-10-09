package com.example.musicplayer

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import java.util.concurrent.Executors

object MediaStoreRepository {
    private const val TAG = "MediaStoreRepository"
    private val executor = Executors.newSingleThreadExecutor()

    fun scan(context: Context, callback: (List<TrackItem>) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val result = ArrayList<TrackItem>()
            try {
                val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.IS_MUSIC,
                )
                val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0"
                val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
    
                appContext.contentResolver.query(
                    collection,
                    projection,
                    selection,
                    null,
                    sort,
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
    
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        val title = c.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown title"
                        val artist = c.getString(artistCol)?.takeIf { it.isNotBlank() } ?: "Unknown artist"
                        val album = c.getString(albumCol)?.takeIf { it.isNotBlank() } ?: "Unknown album"
                        val duration = c.getLong(durationCol)
                        result += TrackItem(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            durationMs = duration,
                            uri = ContentUris.withAppendedId(collection, id),
                        )
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Audio permission missing or revoked during scan", e)
            } catch (e: RuntimeException) {
                Log.e(TAG, "MediaStore scan failed", e)
            }

            callback(result)
        }
    }
}
