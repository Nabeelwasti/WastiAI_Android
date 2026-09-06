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
        assertNotNull(WastiServiceLocator.executionProviderRouter)
        assertNotNull(WastiServiceLocator.capabilityDevelopmentEngine)
        assertNotNull(WastiServiceLocator.skillEvolutionEngine)
        assertNotNull(WastiServiceLocator.integrationAuditRegistry)
        assertNotNull(WastiServiceLocator.toolRegistry)
        assertNotNull(WastiServiceLocator.agentModelProvider)
        assertNotNull(WastiServiceLocator.planner)
        assertNotNull(WastiServiceLocator.errorAnalyzer)

        // Verify registered safe tools in toolRegistry
        assertNotNull(WastiServiceLocator.toolRegistry.get("read_file"))
        assertNotNull(WastiServiceLocator.toolRegistry.get("write_file"))
        assertNotNull(WastiServiceLocator.toolRegistry.get("list_files"))
        assertNotNull(WastiServiceLocator.toolRegistry.get("file_exists"))
        assertNotNull(WastiServiceLocator.toolRegistry.get("create_directory"))
        assertNotNull(WastiServiceLocator.toolRegistry.get("patch_file"))
        assertNotNull(WastiServiceLocator.toolRegistry.get("execute_code"))
    }
}
