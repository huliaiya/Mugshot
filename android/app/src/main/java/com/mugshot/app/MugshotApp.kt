package com.mugshot.app

import android.app.Application

class MugshotApp : Application() {
    companion object {
        lateinit var instance: MugshotApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
