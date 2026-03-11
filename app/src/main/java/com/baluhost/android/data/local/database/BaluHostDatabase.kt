package com.baluhost.android.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.baluhost.android.data.local.database.converters.Converters
import com.baluhost.android.data.local.database.dao.FileActivityDao
import com.baluhost.android.data.local.database.dao.FileDao
import com.baluhost.android.data.local.database.dao.PendingOperationDao
import com.baluhost.android.data.local.database.dao.UserDao
import com.baluhost.android.data.local.database.entities.FileActivityEntity
import com.baluhost.android.data.local.database.entities.FileEntity
import com.baluhost.android.data.local.database.entities.PendingOperationEntity
import com.baluhost.android.data.local.database.entities.UserEntity

/**
 * BaluHost Room Database.
 *
 * Stores cached file metadata, user info, pending operations, and file activity buffer.
 */
@Database(
    entities = [
        FileEntity::class,
        UserEntity::class,
        PendingOperationEntity::class,
        FileActivityEntity::class
    ],
    version = 4, // Incremented for FileActivityEntity
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class BaluHostDatabase : RoomDatabase() {

    abstract fun fileDao(): FileDao
    abstract fun userDao(): UserDao
    abstract fun pendingOperationDao(): PendingOperationDao
    abstract fun fileActivityDao(): FileActivityDao
}
