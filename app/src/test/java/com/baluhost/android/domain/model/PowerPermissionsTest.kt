package com.baluhost.android.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPermissionsTest {

    @Test
    fun `hasAnyPermission is true when only canToggleDesktop is granted`() {
        assertTrue(PowerPermissions(canToggleDesktop = true).hasAnyPermission)
    }

    @Test
    fun `hasAnyPermission is false when only canUnlockSession is granted`() {
        // canUnlockSession is an add-on to canToggleDesktop, never a right of its
        // own — on its own it must not make the power button appear.
        assertFalse(PowerPermissions(canUnlockSession = true).hasAnyPermission)
    }

    @Test
    fun `hasAnyPermission is false when nothing is granted`() {
        assertFalse(PowerPermissions().hasAnyPermission)
    }

    @Test
    fun `hasAnyPermission still reacts to the pre-existing rights`() {
        assertTrue(PowerPermissions(canSoftSleep = true).hasAnyPermission)
        assertTrue(PowerPermissions(canWake = true).hasAnyPermission)
        assertTrue(PowerPermissions(canSuspend = true).hasAnyPermission)
        assertTrue(PowerPermissions(canWol = true).hasAnyPermission)
    }
}
