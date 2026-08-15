package me.capcom.smsgateway.modules.localserver

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.capcom.smsgateway.modules.notifications.NotificationsService
import org.koin.android.ext.android.inject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Runs a bundled `cloudflared` binary (app/src/main/jniLibs/arm64-v8a/libcloudflared.so
 * — a real executable, not a linkable library; see the packagingOptions.jniLibs note
 * in app/build.gradle for why it needs useLegacyPackaging) as a subprocess, so the
 * Local Server is reachable over the internet without a separate Termux install.
 * Started/stopped by LocalServerService alongside WebService, gated on
 * LocalServerSettings.tunnelToken being set — no token means no tunnel, same
 * LAN-only behavior as before this existed.
 */
class TunnelService : Service() {
    private val settings: LocalServerSettings by inject()
    private val notificationsService: NotificationsService by inject()

    private var process: Process? = null
    private var monitorJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        status.postValue(TunnelStatus.Connecting)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notificationsService.makeNotification(
            this,
            NotificationsService.NOTIFICATION_ID_TUNNEL,
            "Cloudflare Tunnel connecting…",
        )
        startForeground(NotificationsService.NOTIFICATION_ID_TUNNEL, notification)

        monitorJob = scope.launch(Dispatchers.IO) {
            runLoop()
        }

        return START_STICKY
    }

    private suspend fun runLoop() {
        val token = settings.tunnelToken
        if (token.isNullOrBlank()) {
            Log.w(TAG, "No tunnel token configured, stopping")
            status.postValue(TunnelStatus.Stopped)
            stopSelfSafely()
            return
        }

        val binaryPath = "${applicationInfo.nativeLibraryDir}/libcloudflared.so"

        var backoffSeconds = 2L
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            try {
                Log.i(TAG, "Starting cloudflared")
                val proc = ProcessBuilder(binaryPath, "tunnel", "run", "--token", token)
                    .redirectErrorStream(true)
                    .start()
                process = proc

                status.postValue(TunnelStatus.Connecting)
                updateNotification("Cloudflare Tunnel connecting…")

                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val text = line ?: continue
                    Log.d(TAG, text)
                    if (text.contains("Registered tunnel connection")) {
                        backoffSeconds = 2L
                        status.postValue(TunnelStatus.Connected)
                        updateNotification("Cloudflare Tunnel connected")
                    }
                }

                val exitCode = proc.waitFor()
                Log.w(TAG, "cloudflared exited with code $exitCode")
            } catch (e: Exception) {
                Log.e(TAG, "cloudflared failed to start", e)
            }

            status.postValue(TunnelStatus.Reconnecting)
            updateNotification("Cloudflare Tunnel reconnecting…")
            delay(backoffSeconds * 1000)
            backoffSeconds = (backoffSeconds * 2).coerceAtMost(60L)
        }
    }

    private fun updateNotification(text: String) {
        val notification = notificationsService.makeNotification(
            this,
            NotificationsService.NOTIFICATION_ID_TUNNEL,
            text,
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(NotificationsService.NOTIFICATION_ID_TUNNEL, notification)
    }

    private fun stopSelfSafely() {
        stopForeground(true)
        stopSelf()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        monitorJob?.cancel()
        process?.destroy()
        process = null
        stopForeground(true)
        status.postValue(TunnelStatus.Stopped)
        super.onDestroy()
    }

    sealed class TunnelStatus {
        object Stopped : TunnelStatus()
        object Connecting : TunnelStatus()
        object Connected : TunnelStatus()
        object Reconnecting : TunnelStatus()
    }

    companion object {
        private const val TAG = "TunnelService"

        private val job = SupervisorJob()
        private val scope = CoroutineScope(job)

        private val status = MutableLiveData<TunnelStatus>(TunnelStatus.Stopped)
        val STATUS: LiveData<TunnelStatus> = status

        fun start(context: Context) {
            val intent = Intent(context, TunnelService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TunnelService::class.java))
        }
    }
}
