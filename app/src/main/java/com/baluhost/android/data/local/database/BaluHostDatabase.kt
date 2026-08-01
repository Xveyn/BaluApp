package com.baluhost.android.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.baluhost.android.data.local.database.converters.Converters
import com.baluhost.android.data.local.database.dao.FileActivityDao
import com.baluhost.android.data.local.database.dao.FileDao
import com.baluhost.android.data.local.database.dao.NotificationDao
import com.baluhost.android.data.local.database.dao.PendingOperationDao
import com.baluhost.android.data.local.database.dao.UserDao
import com.baluhost.android.data.local.database.entities.FileActivityEntity
import com.baluhost.android.data.local.database.entities.FileEntity
import com.baluhost.android.data.local.database.entities.NotificationEntity
import com.baluhost.android.data.local.database.entities.PendingOperationEntity
import com.baluhost.android.data.local.database.entities.UserEntity

/**
 * BaluHost Room Database.
 *
 * Stores cached file metadata, user info, pending operations, file activity buffer,
 * and the local notification cache.
 */
@Database(
    entities = [
        FileEntity::class,
        UserEntity::class,
        PendingOperationEntity::class,
        FileActivityEntity::class,
        NotificationEntity::class
    ],
    version = 5, // Incremented for NotificationEntity
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class BaluHostDatabase : RoomDatabase() {

    abstract fun fileDao(): FileDao
    abstract fun userDao(): UserDao
    abstract fun pendingOperationDao(): PendingOperationDao
    abstract fun fileActivityDao(): FileActivityDao
    abstract fun notificationDao(): NotificationDao
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // A real migration, not the destructive fallback: version 4 holds
        // PendingOperationEntity, i.e. offline operations that have not run
        // yet. Dropping the database would silently discard them.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `notifications` (
                `owner_user_id` INTEGER NOT NULL,
                `id` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `user_id` INTEGER,
                `notification_type` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `message` TEXT NOT NULL,
                `action_url` TEXT,
                `is_read` INTEGER NOT NULL,
                `deleted_at` INTEGER,
                `priority` INTEGER NOT NULL,
                `metadata` TEXT,
                `snoozed_until` INTEGER,
                `local_read_at` INTEGER,
                `local_trashed_at` INTEGER,
                `local_restored_at` INTEGER,
                `local_snoozed_until` INTEGER,
                `source` TEXT NOT NULL,
                `is_partial` INTEGER NOT NULL,
                PRIMARY KEY(`owner_user_id`, `id`)
            )
            """.trimIndent()
        )
    }
}
