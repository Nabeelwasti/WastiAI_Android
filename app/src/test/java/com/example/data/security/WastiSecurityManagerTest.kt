package com.example.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WastiSecurityManagerTest {

    @Before
    fun setUp() {
        WastiSecurityManager.resetDeviceSecurityProviderForTesting()
    }

    @After
    fun tearDown() {
        WastiSecurityManager.resetDeviceSecurityProviderForTesting()
    }

    @Test
    fun testDefaultDeviceSecurityProviderHandlesJvmEnvironmentWithoutCrashing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // DefaultDeviceSecurityProvider should execute on JVM Robolectric without throwing NoSuchMethodError
        val secured = WastiSecurityManager.isDeviceSecured(context)
        // Returns boolean without throwing NoSuchMethodError
        assertFalse(secured)
    }

    @Test
    fun testInjectedDeviceSecurityProvider() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        WastiSecurityManager.deviceSecurityProvider = object : DeviceSecurityProvider {
            override fun isDeviceSecured(ctx: Context): Boolean = true
        }

        assertTrue(WastiSecurityManager.isDeviceSecured(context))

        WastiSecurityManager.deviceSecurityProvider = object : DeviceSecurityProvider {
            override fun isDeviceSecured(ctx: Context): Boolean = false
        }

        assertFalse(WastiSecurityManager.isDeviceSecured(context))
    }
}
