package com.whjk.imagehide.data.room

import androidx.room.Database
import androidx.room.RoomDatabase
import com.whjk.imagehide.data.room.dao.HiddenAlbumDao
import com.whjk.imagehide.data.room.dao.HiddenMediaDao
import com.whjk.imagehide.data.room.dao.SingleHiddenDao
import com.whjk.imagehide.data.room.entity.HiddenAlbumEntity
import com.whjk.imagehide.data.room.entity.HiddenMediaEntity
import com.whjk.imagehide.data.room.entity.SingleHiddenEntity

@Database(
    entities = [HiddenAlbumEntity::class, HiddenMediaEntity::class, SingleHiddenEntity::class],
    version = 2,   // ★ 升 1 → 2
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hiddenAlbumDao(): HiddenAlbumDao
    abstract fun hiddenMediaDao(): HiddenMediaDao
    abstract fun singleHiddenDao(): SingleHiddenDao
}
