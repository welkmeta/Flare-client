package flare.client.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import flare.client.app.R
import flare.client.app.singbox.GeoFileManager
import flare.client.app.singbox.SingBoxManager
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FlareVpnService : VpnService() {

    companion object {
        private const val TAG = "FlareVpnService"
        const val ACTION_START = "flare.client.app.START_VPN"
        const val ACTION_STOP = "flare.client.app.STOP_VPN"
        const val EXTRA_CONFIG = "flare.client.app.CONFIG_JSON"
        const val EXTRA_PROFILE_NAME = "flare.client.app.PROFILE_NAME"
        const val BROADCAST_STATE = "flare.client.app.VPN_STATE"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_ERROR = "error"
        private const val NOTIF_CHANNEL = "flare_vpn"
        private const val NOTIF_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    private var statsJob: kotlinx.coroutines.Job? = null
    private var profileName: String = "Flare Profile"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val configJson = intent.getStringExtra(EXTRA_CONFIG)
        val name = intent.getStringExtra(EXTRA_PROFILE_NAME) ?: "Flare Profile"

        serviceScope.launch {
            commandMutex.withLock {
                when (action) {
                    ACTION_START -> {
                        if (configJson != null) {
                            profileName = name
                            startVpnInternal(configJson, startId)
                        }
                    }
                    ACTION_STOP -> stopVpnInternal(startId)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.launch {
            commandMutex.withLock {
                stopVpnInternal()
            }
        }
    }

    private suspend fun startVpnInternal(configJson: String, startId: Int) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }

        try {
            GeoFileManager.ensureGeoFiles(this)
            
            try {
                Libbox.checkConfig(configJson)
            } catch (e: Exception) {
                Log.e(TAG, "Config validation FAILED: ${e.message}")
            }

            val started = SingBoxManager.start(configJson, this)

            if (!started) {
                broadcastState(false, error = true)
                
                if (!SingBoxManager.isRunning) {
                   stopForeground(STOP_FOREGROUND_REMOVE)
                   stopSelf(startId)
                }
                return
            }

            broadcastState(true)
            startStatsPolling()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN", e)
            broadcastState(false, error = true)
            SingBoxManager.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun startStatsPolling() {
        val settings = flare.client.app.data.SettingsManager(this)
        if (!settings.isStatusNotificationEnabled) return

        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive) {
                SingBoxManager.getTraffic { up, down ->
                    updateNotification(up, down)
                }
                delay(1000)
            }
        }
    }

    private fun updateNotification(up: Long, down: Long) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification(formatSpeed(up), formatSpeed(down)))
    }

    private fun formatSpeed(bytes: Long): String {
        return if (bytes < 1024) {
            "$bytes B/s"
        } else if (bytes < 1024 * 1024) {
            String.format("%.1f KB/s", bytes / 1024.0)
        } else {
            String.format("%.1f MB/s", bytes / (1024.0 * 1024.0))
        }
    }

    private suspend fun stopVpnInternal(startId: Int = -1) {
        statsJob?.cancel()
        SingBoxManager.stop()
        broadcastState(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId != -1) stopSelf(startId)
    }

    private fun broadcastState(connected: Boolean, error: Boolean = false) {
        sendBroadcast(
                Intent(BROADCAST_STATE).apply {
                    putExtra(EXTRA_CONNECTED, connected)
                    putExtra(EXTRA_ERROR, error)
                    `package` = packageName
                }
        )
    }

    private fun buildNotification(upStr: String? = null, downStr: String? = null): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(NOTIF_CHANNEL) == null) {
            manager.createNotificationChannel(
                    NotificationChannel(
                            NOTIF_CHANNEL,
                            "Flare VPN",
                            NotificationManager.IMPORTANCE_LOW
                    )
            )
        }

        val mainIntent = Intent(this, flare.client.app.ui.MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent =
                PendingIntent.getService(
                        this,
                        0,
                        Intent(this, FlareVpnService::class.java).apply { action = ACTION_STOP },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

        val contentText = if (upStr != null && downStr != null) {
            "$upStr ↑ $downStr ↓"
        } else {
            getString(R.string.vpn_active)
        }

        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
                .setContentTitle(profileName)
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_vpn_key)
                .setContentIntent(mainPendingIntent)
                .addAction(R.drawable.ic_vpn_key, getString(R.string.vpn_disconnect), stopIntent)
                .setOngoing(true)
                .build()
    }
}
