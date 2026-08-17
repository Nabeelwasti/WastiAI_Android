package com.example.data.wre

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage9WreCoreTest {

    private lateinit var context: Context
    private lateinit var wreManager: WreManager
    private lateinit var workspaceManager: WreWorkspaceManager
    private lateinit var environmentManager: WreEnvironmentManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        wreManager = WreManager(context)
        workspaceManager = wreManager.workspaceManager
        environmentManager = wreManager.environmentManager
    }

    @Test
    fun testWorkspaceBoundaryAndDirectoryHierarchy() {
        val rootPath = workspaceManager.getRootPath()
        assertNotNull(rootPath)
        assertTrue(rootPath.contains("WastiWorkspace"))

        // Standard directories exist
        for (folder in workspaceManager.standardFolders) {
            val resolved = workspaceManager.resolve(folder)
            assertTrue("Folder '$folder' must be valid", resolved.isSuccess)
            assertTrue("Folder '$folder' must exist", resolved.getOrThrow().exists())
        }

        // Test directory traversal prevention
        val traversalAttempt = workspaceManager.resolve("../../system/etc/passwd")
        assertFalse("Directory traversal outside workspace must be blocked", traversalAttempt.isSuccess)
    }

    @Test
    fun testEnvironmentManagerReport() {
        val variables = environmentManager.getAllVariables()
        assertEquals("wasti", variables["USER"])
        assertEquals("/home/wasti", variables["HOME"])

        val report = environmentManager.getCapabilityReport()
        assertNotNull(report["Terminal Engine"])
        assertNotNull(report["Filesystem Engine"])
    }

    @Test
    fun testNativeCommandExecution_Pwd_Echo_Touch_Cat_Ls() = runBlocking {
        // 1. Test pwd
        val pwdReq = ExecutionRequest(command = "pwd", workingDirectory = "home/wasti")
        val pwdRes = wreManager.execute(pwdReq)
        assertEquals(ExecutionStatus.SUCCESS, pwdRes.status)
        assertEquals(0, pwdRes.exitCode)
        assertTrue(pwdRes.stdout.contains("home/wasti"))

        // 2. Test touch file
        val touchReq = ExecutionRequest(command = "touch test_script.py", workingDirectory = "home/wasti")
        val touchRes = wreManager.execute(touchReq)
        assertEquals(ExecutionStatus.SUCCESS, touchRes.status)
        assertTrue(touchRes.verified)

        // 3. Test echo content into file
        val echoReq = ExecutionRequest(command = "echo print('Hello WRE') > test_script.py", workingDirectory = "home/wasti")
        val echoRes = wreManager.execute(echoReq)
        assertEquals(ExecutionStatus.SUCCESS, echoRes.status)
        assertTrue(echoRes.verified)

        // 4. Test cat file
        val catReq = ExecutionRequest(command = "cat test_script.py", workingDirectory = "home/wasti")
        val catRes = wreManager.execute(catReq)
        assertEquals(ExecutionStatus.SUCCESS, catRes.status)
        assertTrue(catRes.stdout.contains("print('Hello WRE')"))

        // 5. Test ls
        val lsReq = ExecutionRequest(command = "ls", workingDirectory = "home/wasti")
        val lsRes = wreManager.execute(lsReq)
        assertEquals(ExecutionStatus.SUCCESS, lsRes.status)
        assertTrue(lsRes.stdout.contains("test_script.py"))

        // 6. Test rm file
        val rmReq = ExecutionRequest(command = "rm test_script.py", workingDirectory = "home/wasti")
        val rmRes = wreManager.execute(rmReq)
        assertEquals(ExecutionStatus.SUCCESS, rmRes.status)
        assertTrue(rmRes.verified)
    }

    @Test
    fun testProcessManagerTracking() = runBlocking {
        val req = ExecutionRequest(command = "status", workingDirectory = "home/wasti")
        val res = wreManager.execute(req)
        assertEquals(ExecutionStatus.SUCCESS, res.status)

        val processes = wreManager.processManager.listActiveProcesses()
        assertTrue(processes.isNotEmpty())
        assertEquals(ExecutionStatus.SUCCESS, processes.first().status)
    }

    @Test
    fun testSecurityPolicyRejection() = runBlocking {
        val req = ExecutionRequest(command = "rm -rf /")
        val res = wreManager.execute(req)
        assertEquals(ExecutionStatus.DENIED, res.status)
        assertEquals(126, res.exitCode)
        assertTrue(res.stderr.contains("Permission Denied"))
    }
}
