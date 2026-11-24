package com.waterrower.tracker

import android.app.Application
import com.waterrower.tracker.data.AppPreferences
import com.waterrower.tracker.data.WorkoutDatabase
import com.waterrower.tracker.service.RowingSessionHub

class WaterRowerApp : Application() {

    lateinit var preferences: AppPreferences
        private set

    lateinit var database: WorkoutDatabase
        private set

    lateinit var sessionHub: RowingSessionHub
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
        database = WorkoutDatabase.getInstance(this)
        sessionHub = RowingSessionHub(this, database, preferences)
    }
}
