package com.example.data.wre

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.tool.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage9CDynamicNativeRuntimeTest {

    private lateinit var context: Context
    private lateinit var wreManager: WreManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        wreManager = WreManager.getInstance(context)
    }

    @Test
    fun testWreCommandTokenizer() {
        val tokens = WreCommandParser.tokenize("echo \"hello world\" 'single quoted' arg3")
        assertEquals(4, tokens.size)
        assertEquals("echo", tokens[0])
        assertEquals("hello world", tokens[1])
        assertEquals("single quoted", tokens[2])
        assertEquals("arg3", tokens[3])
    }

    @Test
    fun testWrePipelineParsing() {
        val stages = WreCommandParser.parsePipeline("cat file.txt | grep wasti | echo done")
        assertEquals(3, stages.size)
        assertEquals("cat", stages[0].executable)
        assertEquals(listOf("file.txt"), stages[0].args)
        assertEquals("grep", stages[1].executable)
        assertEquals(listOf("wasti"), stages[1].args)
        assertEquals("echo", stages[2].executable)
        assertEquals(listOf("done"), stages[2].args)
    }

    @Test
    fun testPipedExecutionWorkflow() = runBlocking {
        // Step 1: Write text to a test file
        val echoReq = ExecutionRequest(
            command = "echo \"Wasti AI OS Kernel Linux Native\" > test_pipe.txt",
            workingDirectory = "home/wasti"
        )
        val echoRes = wreManager.execute(echoReq)
        assertEquals(ExecutionStatus.SUCCESS, echoRes.status)

        // Step 2: Pipe cat through grep
        val pipeReq = ExecutionRequest(
            command = "cat test_pipe.txt | grep Kernel",
            workingDirectory = "home/wasti"
        )
        val pipeRes = wreManager.execute(pipeReq)
        assertEquals(ExecutionStatus.SUCCESS, pipeRes.status)
        assertTrue(pipeRes.stdout.contains("Kernel"))
    }

    @Test
    fun testPackageManagerAndDynamicToolRegistration() = runBlocking {
        // Step 1: List packages
        val listReq = ExecutionRequest(command = "wre pkg list")
        val listRes = wreManager.execute(listReq)
        assertEquals(ExecutionStatus.SUCCESS, listRes.status)
        assertTrue(listRes.stdout.contains("sysinfo"))

        // Step 2: Execute installed dynamic package directly
        val sysinfoReq = ExecutionRequest(command = "sysinfo")
        val sysinfoRes = wreManager.execute(sysinfoReq)
        assertEquals(ExecutionStatus.SUCCESS, sysinfoRes.status)
        assertTrue(sysinfoRes.stdout.contains("Wasti AI OS Native Runtime"))

        // Step 3: Verify ToolRegistry integration
        val registeredTool = ToolRegistry.getTool("wre_tool_sysinfo")
        assertNotNull(registeredTool)
        val toolOutput = registeredTool?.execute(emptyMap())
        assertTrue(toolOutput?.contains("Wasti AI OS") == true)
    }

    @Test
    fun testDynamicCustomScriptCreationAndExecution() = runBlocking {
        // Step 1: Create a custom tool script in bin/
        val scriptContent = "echo \"Hello from dynamic Wasti tool\""
        val writeReq = ExecutionRequest(
            command = "echo '$scriptContent' > bin/mytool",
            workingDirectory = "home/wasti"
        )
        val writeRes = wreManager.execute(writeReq)
        assertEquals(ExecutionStatus.SUCCESS, writeRes.status)

        // Step 2: Install it as a dynamic package
        val installReq = ExecutionRequest(command = "wre pkg install mytool bin/mytool")
        val installRes = wreManager.execute(installReq)
        assertEquals(ExecutionStatus.SUCCESS, installRes.status)
        assertTrue(installRes.stdout.contains("installed successfully"))

        // Step 3: Run the dynamically created tool
        val runReq = ExecutionRequest(command = "mytool")
        val runRes = wreManager.execute(runReq)
        assertEquals(ExecutionStatus.SUCCESS, runRes.status)
        assertTrue(runRes.stdout.contains("Hello from dynamic Wasti tool"))
    }

    @Test
    fun testAutocompleteEngine() {
        val autocomplete = wreManager.autocompleteEngine

        // Test command autocompletion
        val cmdSuggestions = autocomplete.getSuggestions("sy")
        assertTrue(cmdSuggestions.any { it.text == "sysinfo" })

        // Test wre subcommand autocompletion
        val pkgSuggestions = autocomplete.getSuggestions("wre pkg ")
        assertTrue(pkgSuggestions.any { it.text == "list" || it.displayText.contains("list") })
    }
}
