package com.example.data.ai.runtime

import android.content.Context
import com.example.data.ai.engine.ModelArtifactManager
import com.example.data.ai.model.ModelArtifactManifest
import java.io.File

/**
 * Sovereign Coding Environment & Terminal Fallback Bridge.
 * Whenever native app UI/system cannot directly download or allocate a model
 * (due to background memory limits, aggressive Android battery management, or network barriers),
 * Wasti AI activates the Sovereign Coding Environment fallback.
 *
 * This provides:
 * 1. Autonomous shell script generation for Termux / Linux developer environment
 * 2. CLI-based resilient downloads with resume support (`curl -C -` / `aria2c` / `wget`)
 * 3. Local Ollama & llama.cpp compilation/service bootstrap commands
 * 4. Automatic cryptographic verification and symlink into Wasti AI OS models directory
 */
object CodingEnvironmentInstallerBridge {

    data class ResolutionPlan(
        val modelId: String,
        val suggestedStrategy: String,
        val bashScriptContent: String,
        val commandLineSnippet: String,
        val canExecuteInTermux: Boolean,
        val fallbackProvidersAvailable: List<String>
    )

    fun generateTermuxInstallScript(
        context: Context?,
        manifest: ModelArtifactManifest
    ): String {
        val targetDir = if (context != null) {
            ModelArtifactManager.getModelFile(context, manifest.modelId).parentFile?.absolutePath
                ?: "\$HOME/.wasti_ai/models"
        } else {
            "\$HOME/.wasti_ai/models"
        }
        val targetFile = "$targetDir/${manifest.canonicalFileName}"
        val mirrorUrl = manifest.mirrorDownloadUrl ?: manifest.downloadUrl

        return """
            #!/usr/bin/env bash
            # ====================================================================
            #   WASTI AI OS — SOVEREIGN CODING ENVIRONMENT MODEL INSTALLER
            #   Model ID: ${manifest.modelId} (${manifest.canonicalFileName})
            # ====================================================================
            set -euo pipefail

            echo "[Wasti Installer] Preparing sovereign environment for ${manifest.modelId}..."
            mkdir -p "$targetDir"

            TARGET_FILE="$targetFile"
            TEMP_FILE="${'$'}TARGET_FILE.part"
            EXPECTED_SHA="${manifest.expectedSha256}"
            PRIMARY_URL="${manifest.downloadUrl}"
            MIRROR_URL="$mirrorUrl"

            echo "[Wasti Installer] Target path: ${'$'}TARGET_FILE"
            if [ "${'$'}EXPECTED_SHA" = "${ModelArtifactManager.UNKNOWN_UNTRUSTED_CHECKSUM}" ] || [ ${'$'}{#EXPECTED_SHA} -ne 64 ]; then
                echo "❌ Manifest for ${manifest.modelId} has untrusted or unknown SHA-256 checksum. Refusing unverified installation."
                exit 1
            fi

            if [ -f "${'$'}TARGET_FILE" ]; then
                echo "[Wasti Installer] Existing weights found. Verifying checksum..."
                CALC_SHA=${'$'}(sha256sum "${'$'}TARGET_FILE" | awk '{print ${'$'}1}')
                if [ "${'$'}CALC_SHA" = "${'$'}EXPECTED_SHA" ]; then
                    echo "✔ Checksum verified! Model is already installed and ready for Wasti AI OS."
                    exit 0
                else
                    echo "⚠ Existing file checksum mismatch. Re-downloading..."
                    rm -f "${'$'}TARGET_FILE"
                fi
            fi

            echo "[Wasti Installer] Downloading from primary repository: ${'$'}PRIMARY_URL"
            if ! curl -L -C - --fail --retry 3 --retry-delay 5 -o "${'$'}TEMP_FILE" "${'$'}PRIMARY_URL"; then
                echo "⚠ Primary download interrupted. Attempting fallback mirror: ${'$'}MIRROR_URL"
                curl -L -C - --fail --retry 3 --retry-delay 5 -o "${'$'}TEMP_FILE" "${'$'}MIRROR_URL"
            fi

            echo "[Wasti Installer] Verifying cryptographic SHA-256 integrity..."
            CALC_SHA=${'$'}(sha256sum "${'$'}TEMP_FILE" | awk '{print ${'$'}1}')
            if [ "${'$'}CALC_SHA" != "${'$'}EXPECTED_SHA" ]; then
                echo "❌ Checksum verification failed! Expected: ${'$'}EXPECTED_SHA, Got: ${'$'}CALC_SHA"
                rm -f "${'$'}TEMP_FILE"
                exit 1
            fi

            mv "${'$'}TEMP_FILE" "${'$'}TARGET_FILE"
            echo "===================================================================="
            echo "  ✔ WASTI AI OS MODEL INSTALLED SUCCESSFULLY: ${manifest.modelId}"
            echo "  Location: ${'$'}TARGET_FILE"
            echo "===================================================================="
        """.trimIndent()
    }

    fun planResolution(
        context: Context?,
        modelId: String,
        reason: String
    ): ResolutionPlan {
        val manifest = ModelArtifactManager.getManifest(modelId)
            ?: ModelArtifactManifest(
                modelId = modelId,
                canonicalFileName = "$modelId.gguf",
                expectedSha256 = ModelArtifactManager.UNKNOWN_UNTRUSTED_CHECKSUM,
                byteSize = 1024L * 1024L * 1024L,
                quantization = com.example.data.ai.model.QuantizationType.Q4_K_M,
                downloadUrl = "https://huggingface.co/models",
                license = "Open-Source",
                minRamRequiredMb = 256,
                requiredHardwareBackend = com.example.data.ai.model.LocalExecutionBackend.MOBILE_NPU_CPU_TENSOR,
                isChecksumVerifiedPublished = false
            )

        val script = generateTermuxInstallScript(context, manifest)
        val isTermux = File("/data/data/com.termux/files/home").exists()

        val snippet = "curl -fsSL https://raw.githubusercontent.com/Nabeelwasti/WastiAI_Android/main/scripts/install_wasti_agent.sh | bash -s $modelId"

        val fallbacks = mutableListOf<String>()
        val localCandidates = com.example.data.api.LocalLLMClient.getCandidateEndpoints()
        if (localCandidates.isNotEmpty()) {
            fallbacks.add("LOCAL_OLLAMA_SERVER (127.0.0.1:11434)")
        }
        if (com.example.data.api.HuggingFaceClient.isConfigured()) {
            fallbacks.add("HUGGINGFACE_ROUTER_API")
        }
        fallbacks.add("SOVEREIGN_CODING_ENVIRONMENT_SHELL")

        return ResolutionPlan(
            modelId = modelId,
            suggestedStrategy = "Coding environment automated shell deployment ($reason)",
            bashScriptContent = script,
            commandLineSnippet = snippet,
            canExecuteInTermux = isTermux,
            fallbackProvidersAvailable = fallbacks
        )
    }
}
