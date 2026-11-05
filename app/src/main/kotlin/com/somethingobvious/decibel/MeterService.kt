package com.somethingobvious.decibel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.somethingobvious.decibel.meter.LevelMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the mic open while the app is out of sight. Android silences a background app's mic
 * unless a microphone foreground service is running, and it can only be started from the front.
 */
class MeterService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Meter.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            },
        )
        startForeground(ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (watch == null) watch = watchMeter(manager)
        return START_NOT_STICKY
    }

    private fun watchMeter(manager: NotificationManager): Job =
        scope.launch {
            // One update a second is plenty for a notification and keeps the shade from churning.
            // The mic takes a moment to open, so "not running" only means stop once it has run or failed.
            Meter.state
                .map { Triple(it.isRunning, it.problem, it.stats.ticks / LevelMeter.TICKS_PER_SECOND) }
                .distinctUntilChanged()
                .dropWhile { (isRunning, problem, _) -> !isRunning && problem == Problem.NONE && Meter.isOn }
                .collectLatest { (isRunning, _, _) ->
                    if (isRunning) {
                        manager.notify(
                            ID,
                            notification(
                                Reading.notificationText(
                                    this@MeterService,
                                    Meter.state.value,
                                    SettingsStore.settings.value,
                                ),
                            ),
                        )
                    } else {
                        stopSelf()
                    }
                }
        }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String?): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, MeterService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_meter)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text ?: getString(R.string.notification_starting))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_stop), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "measuring"
        private const val ID = 1
        private const val ACTION_STOP = "com.somethingobvious.decibel.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MeterService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeterService::class.java))
        }
    }
}
