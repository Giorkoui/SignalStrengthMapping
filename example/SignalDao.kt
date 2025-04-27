package com.example.signalstrengthmapper

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SignalDao {
    @Insert
    suspend fun insert(signalEntry: SignalEntry)

    @Query("SELECT * FROM signals")
    suspend fun getAllSignals(): List<SignalEntry>
}
