package com.iamxpp.isaver.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [CustomLocationEntity::class], version = 1, exportSchema = true)
abstract class ISaverDatabase : RoomDatabase() {
    abstract fun customLocationDao(): CustomLocationDao
}
