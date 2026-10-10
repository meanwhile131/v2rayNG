package com.v2ray.ang.ui.settings

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SettingsViewModelTest {
    @Test
    fun vpnSettingsVisibilityFollowsActivityAvailability() = runBlocking(Dispatchers.IO) {
        val packageManager = mock<PackageManager>()
        val application = mock<Application>()
        whenever(application.packageManager).thenReturn(packageManager)
        var activity: ComponentName? = null

        mockConstruction(Intent::class.java) { intent, context ->
            assertEquals(listOf(Settings.ACTION_VPN_SETTINGS), context.arguments())
            whenever(intent.resolveActivity(packageManager)).thenAnswer { activity }
        }.use {
            val viewModel = SettingsViewModel(application)
            assertFalse(viewModel.systemVpnSettingsAvailable.value)
            viewModel.refreshSystemVpnSettingsAvailability()
            assertFalse(viewModel.systemVpnSettingsAvailable.value)

            activity = mock<ComponentName>()
            viewModel.refreshSystemVpnSettingsAvailability()
            assertTrue(viewModel.systemVpnSettingsAvailable.value)

            activity = null
            viewModel.refreshSystemVpnSettingsAvailability()
            assertFalse(viewModel.systemVpnSettingsAvailable.value)
        }
    }

    @Test
    fun autoSelectInterval_acceptsWholeMinutesInRange() {
        assertEquals("1", AutoSelectIntervalValidator.normalize("1"))
        assertEquals("5", AutoSelectIntervalValidator.normalize("5"))
        assertEquals("15", AutoSelectIntervalValidator.normalize(" 15 "))
        assertEquals("1440", AutoSelectIntervalValidator.normalize("1440"))
    }

    @Test
    fun autoSelectInterval_rejectsNonNumericInput() {
        assertNull(AutoSelectIntervalValidator.normalize(""))
        assertNull(AutoSelectIntervalValidator.normalize("   "))
        assertNull(AutoSelectIntervalValidator.normalize("abc"))
        assertNull(AutoSelectIntervalValidator.normalize("5.5"))
    }

    @Test
    fun autoSelectInterval_rejectsOutOfRangeValues() {
        assertNull(AutoSelectIntervalValidator.normalize("0"))
        assertNull(AutoSelectIntervalValidator.normalize("-1"))
        assertNull(AutoSelectIntervalValidator.normalize("1441"))
    }
}
