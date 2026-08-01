package com.example.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.assistant.backend.BackendClient
import kotlinx.coroutines.launch

/**
 * DevAssistantScreen: UI to compose a dev prompt and request a patch PR via backend.
 * This is a minimal first pass; it will be expanded with file selectors and patch preview.
 */
@Composable
fun DevAssistantScreen() {
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("idle") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Dev Assistant — generate code patch")
        androidx.compose.material3.TextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = {
            scope.launch {
                status = "calling backend..."
                // Build a simple payload for the LLM to generate a patch (backend will be responsible for calling LLM)
                val baseUrl = "https://your-backend.example.com" // TODO: replace with BuildConfig.BASE_BACKEND_URL
                val payloadJson = "{\"provider\":\"openai\",\"payload\":{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"${prompt.replace("\"","\\\"")}\"}]}}"
                val resp = BackendClient.callLLM(baseUrl, "openai", payloadJson)
                status = resp ?: "failed"
            }
        }) {
            Text(text = "Generate Patch")
        }

        Text(text = "Status: ${status}", modifier = Modifier.padding(top = 12.dp))
    }
}
