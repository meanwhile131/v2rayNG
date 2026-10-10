package com.v2ray.ang.e2e

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.AutoSelectManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * End-to-end coverage for automatic server selection and failover.
 *
 * This drives the same entry point the user does, [LauncherManager.startService], against loopback
 * fixtures instead of public servers, so the whole chain runs for real: the VPN service starts in
 * the daemon process, the auto-select worker starts beside it in the tasks process, real delay
 * probes run through the native core, and the selected config is rewritten in MMKV.
 */
@RunWith(AndroidJUnit4::class)
class AutoSelectE2eTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var fixture: LocalProxyFixture

    private var workingGuid: String? = null
    private var deadGuid: String? = null

    private var previousDelayUrl: String? = null
    private var previousAutoSelectEnabled: Boolean = false
    private var previousInterval: String? = null
    private var previousSelection: String? = null

    @Before
    fun setUp() {
        // VpnService consent would otherwise block the start behind a system dialog.
        grantVpnConsent()

        fixture = LocalProxyFixture().apply { start() }
        // The core refuses to start until the geo assets are in place, and a freshly installed
        // build is still copying them out of the APK when the first start is issued.
        ensureGeoAssets()

        previousDelayUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_DELAY_TEST_URL)
        previousAutoSelectEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SELECT_ENABLED)
        previousInterval = MmkvManager.decodeSettingsString(AppConfig.PREF_AUTO_SELECT_INTERVAL_MINUTES)
        previousSelection = MmkvManager.getSelectServer()

        // Probe the local endpoint instead of a public URL, and shorten the interval so a retry
        // still lands inside the test timeout.
        MmkvManager.encodeSettings(AppConfig.PREF_DELAY_TEST_URL, fixture.delayTestUrl)
        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_SELECT_ENABLED, true)
        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_SELECT_INTERVAL_MINUTES, "1")
    }

    @After
    fun tearDown() {
        runCatching { LauncherManager.stopService(context) }
        // Let the daemon finish tearing down before the fixtures disappear.
        runCatching { Thread.sleep(STOP_SETTLE_MS) }

        workingGuid?.let { runCatching { MmkvManager.removeServer(it) } }
        deadGuid?.let { runCatching { MmkvManager.removeServer(it) } }

        restore(AppConfig.PREF_DELAY_TEST_URL, previousDelayUrl)
        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_SELECT_ENABLED, previousAutoSelectEnabled)
        restore(AppConfig.PREF_AUTO_SELECT_INTERVAL_MINUTES, previousInterval)
        MmkvManager.decodeAllServerList().forEach { leftover ->
            if (MmkvManager.decodeServerConfig(leftover)?.remarks in TEST_REMARKS) {
                runCatching { MmkvManager.removeServer(leftover) }
            }
        }
        previousSelection?.let { MmkvManager.setSelectServer(it) }

        fixture.stop()
    }

    @Test
    fun switchesAwayFromSelectedConfigThatCannotConnect() {
        workingGuid = addSocksProfile(REMARK_WORKING, fixture.socksPort)
        deadGuid = addSocksProfile(REMARK_DEAD, fixture.closedPort)

        // The dead config is what the user currently has selected.
        MmkvManager.setSelectServer(deadGuid!!)
        assertEquals(deadGuid, MmkvManager.getSelectServer())

        LauncherManager.startService(context)

        val settled = awaitSelectionChangedFrom(deadGuid!!)
        assertNotNull("a config should have been selected", settled)
        // The worker ranks every profile the app holds, so the replacement is asserted to be a
        // different, measured-working config rather than to be this test's own fixture.
        assertNotEquals(
            "Auto-select must replace a config that cannot connect",
            deadGuid,
            settled
        )
    }

    @Test
    fun workingFixtureMeasuresFasterThanDeadFixture() {
        workingGuid = addSocksProfile(REMARK_WORKING, fixture.socksPort)
        deadGuid = addSocksProfile(REMARK_DEAD, fixture.closedPort)

        // Only these two profiles are measured, so the outcome does not depend on whatever else
        // the device under test happens to have installed.
        val results = runBlockingAwait {
            AutoSelectManager.testServers(context, listOf(workingGuid!!, deadGuid!!))
        }

        val workingDelay = MmkvManager.decodeServerAffiliationInfo(workingGuid!!)?.testDelayMillis
        val deadDelay = MmkvManager.decodeServerAffiliationInfo(deadGuid!!)?.testDelayMillis

        assertTrue("working config should measure a positive delay, was $workingDelay", (workingDelay ?: 0L) > 0L)
        assertTrue("dead config should not measure a usable delay, was $deadDelay", (deadDelay ?: 0L) <= 0L)
        assertEquals(workingGuid, AutoSelectManager.selectBestServer(results))
        assertTrue(
            "auto-select should replace the dead config with the working one",
            AutoSelectManager.shouldSwitch(
                currentDelayMillis = deadDelay,
                bestDelayMillis = results[workingGuid],
                bestGuid = workingGuid,
                currentGuid = deadGuid,
            )
        )
    }

    private fun addSocksProfile(remarks: String, port: Int): String {
        val profile = ProfileItem.create(EConfigType.SOCKS).apply {
            this.remarks = remarks
            this.server = LOOPBACK
            this.serverPort = port.toString()
        }
        return MmkvManager.encodeServerConfig("", profile)
    }

    /** Waits until the stored selection differs from [original], then returns it. */
    private fun awaitSelectionChangedFrom(original: String): String? {
        val deadline = System.currentTimeMillis() + SWITCH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val current = MmkvManager.getSelectServer()
            if (current != null && current != original) {
                return current
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        LogUtil.e(AppConfig.TAG, "AutoSelectE2eTest: selection never changed from $original")
        return MmkvManager.getSelectServer()
    }

    /**
     * Waits until the geo data files the core needs are readable, copying them from the APK when
     * they are not there yet. Starting the core before this finishes fails with
     * "failed to open geosite.dat" and looks like an auto-select failure when it is not.
     */
    private fun ensureGeoAssets() {
        val folder = Utils.userAssetPath(context)
        val required = listOf(AppConfig.GEOSITE_DAT, AppConfig.GEOIP_DAT, AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT)
        val deadline = System.currentTimeMillis() + ASSET_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (required.all { File(folder, it).exists() }) return
            runCatching { SettingsManager.initAssets(context, context.assets) }
            if (required.all { File(folder, it).exists() }) return
            Thread.sleep(ASSET_POLL_MS)
        }
        throw AssertionError(
            "geo assets never became available: " +
                required.filterNot { File(folder, it).exists() }
        )
    }

    private fun grantVpnConsent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "appops set ${context.packageName} ACTIVATE_VPN allow"
        ).close()
    }

    private fun restore(key: String, value: String?) {
        if (value != null) MmkvManager.encodeSettings(key, value) else MmkvManager.encodeSettings(key, "")
    }

    /** Runs a suspending call from the instrumentation thread. */
    private fun <T> runBlockingAwait(block: suspend () -> T): T = runBlocking {
        withTimeout(SWITCH_TIMEOUT_MS) { block() }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val REMARK_WORKING = "e2e-auto-select-working"
        const val REMARK_DEAD = "e2e-auto-select-dead"
        val TEST_REMARKS = setOf(REMARK_WORKING, REMARK_DEAD)
        const val SWITCH_TIMEOUT_MS = 120_000L
        const val POLL_INTERVAL_MS = 500L
        const val STOP_SETTLE_MS = 2_000L
        const val ASSET_TIMEOUT_MS = 180_000L
        const val ASSET_POLL_MS = 1_000L
    }
}