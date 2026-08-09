package com.ashcastle.duckyslicer

import android.app.Application
import android.content.Context

class DuckySlicerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        sharedContext = getApplicationContext()
    }

    internal companion object {
        @Volatile
        private var sharedContext: Context? = null

        fun context(): Context = checkNotNull(sharedContext) {
            "Application context is unavailable"
        }
    }
}
