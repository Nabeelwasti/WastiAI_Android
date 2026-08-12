package com.example.data.agent.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkspaceManagerSecurityTest {

    private lateinit var context: Context
    private lateinit var workspaceManager: WorkspaceManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workspaceManager = WorkspaceManager(context)
    }

    @Test
    fun test1_directoryTraversalRelative_blocked() {
        val result = workspaceManager.resolvePathSafely("../../secret.txt")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun test2_deepDirectoryTraversal_blocked() {
        val result = workspaceManager.resolvePathSafely("../../../data/system/packages.xml")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun test3_absolutePathSystem_blocked() {
        val result = workspaceManager.resolvePathSafely("/etc/passwd")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun test4_absolutePathStorage_blocked() {
        val result = workspaceManager.resolvePathSafely("/sdcard/Download/sensitive.pdf")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun test5_validRelativeWorkspacePath_allowed() {
        val result = workspaceManager.resolvePathSafely("src/Main.kt")
        assertTrue(result.isSuccess)
        val file = result.getOrNull()
        assertNotNull(file)
        assertTrue(file!!.canonicalPath.startsWith(workspaceManager.getWorkspaceRootPath()))
    }

    @Test
    fun test6_validInternalDotDotTraversal_allowed() {
        val result = workspaceManager.resolvePathSafely("src/../src/Main.kt")
        assertTrue(result.isSuccess)
        val file = result.getOrNull()
        assertNotNull(file)
        assertTrue(file!!.canonicalPath.endsWith("src/Main.kt"))
    }

    @Test
    fun test7_siblingDirectoryPrefixAttack_blocked() {
        // Construct a path that points to a sibling directory starting with the same string prefix
        val result = workspaceManager.resolvePathSafely("../wasti_workspace_evil/payload.kt")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun test8_symlinkEscape_blocked() {
        // Create an external target outside workspace
        val externalTarget = File(context.filesDir, "external_target.txt")
        externalTarget.writeText("sensitive external data")

        // In the workspace, create a symlink to externalTarget if OS permits
        val workspaceDir = File(workspaceManager.getWorkspaceRootPath())
        val symlinkFile = File(workspaceDir, "symlink_out.txt")

        try {
            java.nio.file.Files.createSymbolicLink(
                symlinkFile.toPath(),
                externalTarget.toPath()
            )

            // Verify that resolving through symlink_out.txt is BLOCKED because canonical path points outside workspace
            val result = workspaceManager.resolvePathSafely("symlink_out.txt")
            assertTrue("Symlink escape must be blocked", result.isFailure)
            assertTrue(result.exceptionOrNull() is SecurityException)
        } catch (e: UnsupportedOperationException) {
            // If environment doesn't support symlink creation, log or ignore gracefully
        } catch (e: SecurityException) {
            // Environment blocked symlink creation
        } finally {
            if (symlinkFile.exists()) symlinkFile.delete()
            if (externalTarget.exists()) externalTarget.delete()
        }
    }
}
