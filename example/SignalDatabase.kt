package com.example.signalstrengthmapper

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SignalEntry::class], version = 1)
abstract class SignalDatabase : RoomDatabase() {
    abstract fun signalDao(): SignalDao

    companion object {
        @Volatile
        private var INSTANCE: SignalDatabase? = null

        fun getDatabase(context: Context): SignalDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SignalDatabase::class.java,
                    "signal_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
