package com.example.data.wre

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.TerminalSessionEntity
import com.example.data.db.WastiDatabase
import com.example.data.tool.ToolRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Stage 9D: Wasti Code Studio + Capability Marketplace + Terminal Session Persistence Tests
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage9DCodeStudioTest {

    private lateinit var context: Context
    private lateinit var wreManager: WreManager
    private lateinit var db: WastiDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        wreManager = WreManager.getInstance(context)
        db = Room.inMemoryDatabaseBuilder(context, WastiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testTerminalSessionPersistence() = runBlocking {
        val dao = db.terminalSessionDao()
        
        // Step 1: Insert terminal execution entry
        val entry = TerminalSessionEntity(
            sessionId = "session_test",
            command = "echo 'Hello Wasti Dev'",
            output = "Hello Wasti Dev",
            stderr = "",
            workingDirectory = "home/wasti",
            status = "SUCCESS",
            exitCode = 0,
            durationMs = 12L,
            verified = true,
            verificationEvidence = "WRE verified stdout output",
            timestamp = System.currentTimeMillis()
        )
        dao.insertSessionEntry(entry)

        // Step 2: Query history
        val history = dao.getHistoryForSession("session_test").first()
        assertEquals(1, history.size)
        assertEquals("echo 'Hello Wasti Dev'", history[0].command)
        assertEquals("Hello Wasti Dev", history[0].output)
        assertTrue(history[0].verified)

        // Step 3: Clear session
        dao.deleteHistoryForSession("session_test")
        val clearedHistory = dao.getHistoryForSession("session_test").first()
        assertTrue(clearedHistory.isEmpty())
    }

    @Test
    fun testWastiPackageExportAndImport() = runBlocking {
        val pkgMgr = wreManager.packageManager

        // Step 1: Install a custom package
        val installRes = pkgMgr.installOrUpdateScriptPackage(
            name = "custom_calc",
            scriptContent = "echo \"CALC_RESULT: 42\"",
            description = "Custom calculator capability",
            version = "1.2.0"
        )
        assertTrue(installRes.isSuccess)

        // Step 2: Export package to .wasti bundle
        val exportRes = pkgMgr.exportPackage("custom_calc")
        assertTrue(exportRes.isSuccess)
        val bundleFile = exportRes.getOrThrow()
        assertTrue(bundleFile.exists())
        assertTrue(bundleFile.name.endsWith(".wasti"))

        val bundleContent = bundleFile.readText()
        assertTrue(bundleContent.contains("custom_calc"))
        assertTrue(bundleContent.contains("CALC_RESULT: 42"))

        // Step 3: Remove package
        assertTrue(pkgMgr.removePackage("custom_calc"))
        assertNull(pkgMgr.getPackage("custom_calc"))

        // Step 4: Re-install from .wasti bundle
        val reimportRes = pkgMgr.installWastiPackage(bundleFile.absolutePath)
        assertTrue(reimportRes.isSuccess)
        val reimportedPkg = pkgMgr.getPackage("custom_calc")
        assertNotNull(reimportedPkg)
        assertEquals("1.2.0", reimportedPkg?.version)

        // Step 5: Execute reimported capability in WRE
        val execReq = ExecutionRequest(command = "custom_calc")
        val execRes = wreManager.execute(execReq)
        assertEquals(ExecutionStatus.SUCCESS, execRes.status)
        assertTrue(execRes.stdout.contains("CALC_RESULT: 42"))
    }

    @Test
    fun testWrePkgCliCommands() = runBlocking {
        // Step 1: Create script and install package via CLI
        val writeReq = ExecutionRequest(
            command = "echo 'echo \"CLI_SUCCESS\"' > scripts/cli_tool.sh",
            workingDirectory = "home/wasti"
        )
        val writeRes = wreManager.execute(writeReq)
        assertEquals(ExecutionStatus.SUCCESS, writeRes.status)

        val installCliReq = ExecutionRequest(command = "wre pkg install cli_tool scripts/cli_tool.sh")
        val installCliRes = wreManager.execute(installCliReq)
        assertEquals(ExecutionStatus.SUCCESS, installCliRes.status)
        assertTrue(installCliRes.stdout.contains("installed successfully"))

        // Step 2: Inspect package info via CLI
        val infoReq = ExecutionRequest(command = "wre pkg info cli_tool")
        val infoRes = wreManager.execute(infoReq)
        assertEquals(ExecutionStatus.SUCCESS, infoRes.status)
        assertTrue(infoRes.stdout.contains("Package: cli_tool"))

        // Step 3: Export package via CLI
        val exportCliReq = ExecutionRequest(command = "wre pkg export cli_tool")
        val exportCliRes = wreManager.execute(exportCliReq)
        assertEquals(ExecutionStatus.SUCCESS, exportCliRes.status)
        assertTrue(exportCliRes.stdout.contains("Exported"))

        // Step 4: Verify ToolRegistry integration
        val registered = ToolRegistry.getTool("wre_tool_cli_tool")
        assertNotNull(registered)
        val out = registered?.execute(emptyMap())
        assertTrue(out?.contains("CLI_SUCCESS") == true)
    }

    @Test
    fun testStage9DAutocompletions() {
        val autocomplete = wreManager.autocompleteEngine

        // Test wre pkg autocompletions including export and info
        val pkgSuggestions = autocomplete.getSuggestions("wre pkg ")
        val suggestionTexts = pkgSuggestions.map { it.text }
        assertTrue(suggestionTexts.contains("export"))
        assertTrue(suggestionTexts.contains("info"))
        assertTrue(suggestionTexts.contains("install"))
        assertTrue(suggestionTexts.contains("list"))
    }
}
