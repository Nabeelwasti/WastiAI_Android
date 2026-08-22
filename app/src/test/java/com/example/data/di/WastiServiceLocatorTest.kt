package com.example.data.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WastiServiceLocatorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WastiServiceLocator.init(context)
    }

    @Test
    fun testServiceLocatorProvidesCoreInstances() {
        assertNotNull(WastiServiceLocator.database)
        assertNotNull(WastiServiceLocator.repository)
        assertNotNull(WastiServiceLocator.wreManager)
        assertNotNull(WastiServiceLocator.securityPolicyEngine)
        assertNotNull(WastiServiceLocator.agentEventBus)
        assertNotNull(WastiServiceLocator.agentRuntime)
        assertNotNull(WastiServiceLocator.toolRouter)
    }
}
