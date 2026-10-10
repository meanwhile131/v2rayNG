package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.AutoSelectManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Health-checks the running config and switches to another working config when the current one
 * stops working.
 *
 * Runs in the `:tasks` process alongside [CoreTestService], so probes are measured directly against
 * each candidate server. The app package is excluded from its own tunnel in both VPN and root mode,
 * so these probes do not loop back through the running config they are meant to judge.
 *
 * Daemon state is only ever read through [MessageHelper]; this process must not infer whether the
 * core is running from a local object or from a successful send.
 */
class AutoSelectWorkerService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO + CoroutineName("AutoSelectWorker"))
    private var monitorJob: Job? = null

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    override fun onCreate() {
        super.onCreate()
        CoreNativeManager.initCoreEnv(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        LogUtil.i(AppConfig.TAG, "AutoSelectWorkerService: being destroyed")
        serviceJob.cancel()
        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.startForeground(
            this,
            NotificationChannelType.AUTO_SELECT,
            getString(R.string.app_name),
            getString(R.string.title_auto_select_monitoring)
        )

        startMonitoring()
        return START_NOT_STICKY
    }

    private fun startMonitoring() {
        // A second start command must not produce a second loop.
        if (monitorJob?.isActive == true) return

        monitorJob = serviceScope.launch {
            // isActive, not while(true): cancelling the scope on destroy must end this loop.
            var firstCheck = true
            while (isActive) {
                val enabled = SettingsManager.isAutoSelectEnabled()
                if (enabled) {
                    // The first check after a start lands the app on the fastest working config.
                    // It is skipped when the running config is already the one auto-select chose,
                    // so the restart caused by that switch cannot start another one.
                    val preferFastest = AutoSelectManager.shouldPreferFastestOnStart(
                        currentGuid = MmkvManager.getSelectServer(),
                        lastAutoSelectedGuid = SettingsManager.getAutoSelectChosenGuid(),
                        isFirstCheck = firstCheck,
                    )
                    runHealthCheck(preferFastest = preferFastest)
                    firstCheck = false
                }
                delay(SettingsManager.getAutoSelectIntervalMinutes() * 60_000L)
            }
        }
    }

    /**
     * Runs one health check. Returns without switching when the daemon reports the core is not
     * running, so a stale worker cannot change the selected config for a stopped service.
     */
    private suspend fun runHealthCheck(preferFastest: Boolean = false) {
        if (!isDaemonRunning()) {
            LogUtil.d(AppConfig.TAG, "AutoSelectWorkerService: daemon not running, skip check")
            return
        }

        val results = AutoSelectManager.testAllServers(this)
        if (results.isEmpty()) {
            LogUtil.i(AppConfig.TAG, "AutoSelectWorkerService: no measurable server, keep current config")
            return
        }

        val currentGuid = MmkvManager.getSelectServer()
        val bestGuid = AutoSelectManager.selectBestServer(results)
        val currentDelay = currentGuid?.let { results[it] }

        val shouldSwitch = AutoSelectManager.shouldSwitch(
            currentDelayMillis = currentDelay,
            bestDelayMillis = bestGuid?.let { results[it] },
            bestGuid = bestGuid,
            currentGuid = currentGuid,
            preferFastest = preferFastest,
        )

        if (!shouldSwitch || bestGuid == null || bestGuid == currentGuid) {
            return
        }

        val remarks = MmkvManager.decodeServerConfig(bestGuid)?.remarks.orEmpty()
        LogUtil.i(
            AppConfig.TAG,
            "AutoSelectWorkerService: switching $currentGuid -> $bestGuid ($remarks), " +
                "current=${currentDelay ?: -1}ms best=${results[bestGuid]}ms"
        )
        MmkvManager.setSelectServer(bestGuid)
        // Mark the choice so the restart this switch causes does not immediately re-optimize.
        SettingsManager.setAutoSelectChosenGuid(bestGuid)
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_STATE_RESTART, "")
    }

    /**
     * Asks the daemon to acknowledge that its core is running.
     *
     * The daemon answers this ordered broadcast with RESULT_OK only while its core runs, so the
     * result code alone carries the answer; an absent daemon leaves the default RESULT_CANCELED.
     *
     * @return True only when a daemon receiver answered with a running core.
     */
    private suspend fun isDaemonRunning(): Boolean {
        return try {
            withTimeoutOrNull(DAEMON_ACK_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    MessageHelper.sendMsg2ServiceForResult(
                        this@AutoSelectWorkerService,
                        AppConfig.MSG_AUTO_SELECT_RUNNING,
                        "",
                    ) { handled ->
                        if (continuation.isActive) continuation.resume(handled)
                    }
                }
            } ?: false
        } catch (cancelled: CancellationException) {
            // Teardown of the owning scope is not a daemon failure.
            throw cancelled
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "AutoSelectWorkerService: daemon state query failed", e)
            false
        }
    }

    private companion object {
        const val DAEMON_ACK_TIMEOUT_MS = 5_000L
    }
}