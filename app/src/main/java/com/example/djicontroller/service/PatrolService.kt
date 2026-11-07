package com.example.djicontroller.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.djicontroller.MainActivity
import com.example.djicontroller.R
import com.example.djicontroller.appContainer
import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.model.ConnectionState
import com.example.djicontroller.core.model.CycleState
import com.example.djicontroller.core.model.label
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Usługa pierwszoplanowa (sekcja 6): utrzymuje proces i CPU aktywne przez cały cykl,
 * także przy wygaszonym ekranie. Sama nie steruje dronem — logika żyje w AppContainer.
 *
 * Typ `connectedDevice`: aplikacja obsługuje drona podłączonego przez akcesorium USB (RC-N2).
 */
class PatrolService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Uruchamianie…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        acquireWakeLock()

        val container = appContainer
        container.logger.system("service_started")
        combine(container.orchestrator.state, container.connection.state, container.telemetry.state) { cycle, conn, drone ->
            val link = when (conn) {
                is ConnectionState.Connected -> "dron połączony"
                ConnectionState.Disconnected -> "brak drona"
                is ConnectionState.RegistrationFailed -> "błąd rejestracji DJI"
                else -> "uruchamianie MSDK"
            }
            val battery = drone.batteryPercent?.let { " · bateria $it%" } ?: ""
            "${cycle.label()} · $link$battery"
        }
            .distinctUntilChanged()
            .onEach { text -> notify(text) }
            .launchIn(lifecycleScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP_SERVICE) {
            val state = appContainer.orchestrator.state.value
            if (state == CycleState.Ready || state is CycleState.Failure && !appContainer.telemetry.state.value.possiblyAirborne) {
                appContainer.logger.system("service_stopped_by_user")
                stopSelf()
            } else {
                notify("Nie można zamknąć w trakcie cyklu (${state.label()})")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // Bez limitu czasu: cykl (lot + ładowanie) trwa godzinami; blokada jest zwalniana w onDestroy.
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Patrol:cycle").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Cykl patrolu", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Stan drona i cyklu pracy"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PatrolService::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_patrol)
            .setContentTitle("Patrol DJI Mini 4 Pro")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Zamknij", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notify(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: SecurityException) {
            // Brak zgody POST_NOTIFICATIONS — usługa działa dalej, tylko bez aktualizacji powiadomienia.
        }
    }

    companion object {
        private const val CHANNEL_ID = "patrol_cycle"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP_SERVICE = "com.example.djicontroller.STOP_SERVICE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PatrolService::class.java))
        }
    }
}
