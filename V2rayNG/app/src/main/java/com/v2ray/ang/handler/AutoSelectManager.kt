package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.service.RealPingWorkerService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tests real-ping delay for the app's profiles and picks a config to run.
 *
 * Probing is measured by [RealPingWorkerService], so a result is a real delay or a failure rather
 * than a TCP-only guess. Group profiles are skipped: their member outbounds are probed individually.
 */
object AutoSelectManager {

    /**
     * Result recorded for a config that has not been measured yet, and the floor below which a
     * delay counts as measured. Negative values are probe failures and 0 is an unmeasured config,
     * so only a delay above this is a usable result.
     */
    private const val DELAY_UNKNOWN = 0L

    /**
     * A replacement must beat the running config by this share of the current delay to be worth
     * restarting the tunnel. Anything smaller is treated as measurement jitter.
     */
    private const val MIN_IMPROVEMENT_RATIO = 0.5

    /**
     * Tests every config the app knows about and returns GUID to delay in milliseconds.
     * A missing entry means that config produced no result at all.
     */
    suspend fun testAllServers(context: Context): Map<String, Long> = withContext(Dispatchers.IO) {
        testServers(context, MmkvManager.decodeAllServerList())
    }

    /**
     * Tests the given configs and returns GUID to delay in milliseconds.
     *
     * Complex profiles (custom, policy group, proxy chain) are excluded because they have no
     * single address to dial; the real ping worker measures their members instead.
     */
    suspend fun testServers(context: Context, guids: List<String>): Map<String, Long> =
        withContext(Dispatchers.IO) {
            val testable = guids.filter { guid ->
                MmkvManager.decodeServerConfig(guid)?.configType?.isComplexType() == false
            }
            if (testable.isEmpty()) return@withContext emptyMap()

            val completed = CompletableDeferred<Map<String, Long>>()
            val results = LinkedHashMap<String, Long>(testable.size)
            val worker = RealPingWorkerService(
                context = context,
                guids = testable,
                onEvent = { event ->
                    when (event) {
                        is RealPingEvent.Result -> {
                            results[event.guid] = event.delayMillis
                            // Persist so the server list shows the same delays this decision used.
                            MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis)
                        }
                        is RealPingEvent.Finish -> completed.complete(results.toMap())
                        // Progress is ignored; only the final ranking matters here.
                        is RealPingEvent.Progress -> Unit
                    }
                }
            )
            worker.start()
            completed.await()
        }

    /**
     * Selects the config with the lowest measured delay.
     *
     * @return The winning GUID, or null when nothing is known to work.
     */
    fun selectBestServer(results: Map<String, Long>): String? =
        results.entries
            .filter { it.value > DELAY_UNKNOWN }
            .minByOrNull { it.value }
            ?.key

    /**
     * Decides whether the health check that follows a start should take the fastest config outright.
     *
     * A switch restarts the tunnel, which starts a new worker whose first check would otherwise
     * prefer fastest again. Since re-measuring can rank configs differently, that could ping-pong
     * between configs and restart the tunnel repeatedly. Requiring the running config to differ
     * from the one auto-select last chose keeps a self-caused restart from re-entering this path,
     * while a genuine manual selection still gets optimized on the next start.
     *
     * @param currentGuid The config currently running.
     * @param lastAutoSelectedGuid The config auto-select last chose, if any.
     * @param isFirstCheck Whether this is the first check of this worker.
     */
    fun shouldPreferFastestOnStart(
        currentGuid: String?,
        lastAutoSelectedGuid: String?,
        isFirstCheck: Boolean,
    ): Boolean = isFirstCheck && currentGuid != null && currentGuid != lastAutoSelectedGuid

    /**
     * Returns the first profile that can actually be launched, or null when none exists.
     *
     * Used so a start never depends on the user having picked a config by hand: when nothing
     * usable is selected the first available profile is taken instead.
     */
    fun firstUsableGuid(): String? =
        MmkvManager.decodeAllServerList().firstOrNull { MmkvManager.decodeServerConfig(it) != null }

    /**
     * Decides whether the running config should be replaced after a health check.
     *
     * Switching is deliberately conservative: a config that still answers is only replaced when a
     * measured config is clearly faster, so ordinary delay jitter does not churn the tunnel.
     *
     * @param currentDelayMillis Last measured delay of the running config, or null when it has no result.
     * @param bestDelayMillis Last measured delay of the fastest working config, or null when none works.
     * @param bestGuid GUID of the fastest working config.
     * @param currentGuid GUID of the running config.
     * @param preferFastest When true, take the fastest working config even if the current one works.
     *   Used for the check that follows a start, so the app lands on the best config on its own.
     * @return True when the running config should be replaced by [bestGuid].
     */
    fun shouldSwitch(
        currentDelayMillis: Long?,
        bestDelayMillis: Long?,
        bestGuid: String?,
        currentGuid: String?,
        preferFastest: Boolean = false,
    ): Boolean {
        if (bestGuid == null || bestDelayMillis == null) return false
        if (bestGuid == currentGuid) return false
        // A candidate that did not measure is not an improvement.
        if (bestDelayMillis <= DELAY_UNKNOWN) return false
        if (preferFastest) return true

        val current = currentDelayMillis
            // A running config with no usable measurement cannot be shown to work.
            ?.takeIf { it > DELAY_UNKNOWN }
            ?: return true

        return bestDelayMillis < current * MIN_IMPROVEMENT_RATIO
    }
}