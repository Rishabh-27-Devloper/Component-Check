package com.componentcheck

import android.app.Application
import com.componentcheck.worker.MonitorWorker
import com.componentcheck.worker.NotificationHelper

class InstagramMonitorApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Create high-priority notification channel for Instagram alerts
        NotificationHelper.createNotificationChannel(this)

        // Schedule 15-minute background periodic monitoring worker
        MonitorWorker.schedulePeriodicWork(this)
    }
}
