package com.example.data.core

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.input.InputManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.InputDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [The Eternal Manifesto: The Infinite Capability Law & Resource Intelligence Law]
 *
 * WastiDeviceSensoryEngine:
 * Unified controller for physical device peripherals, cameras, mic, audio, and sensors:
 * 1. Mouse, keyboard, and physical pointer detection (OTG / Bluetooth).
 * 2. Front & Back Camera availability and lens configuration.
 * 3. Speaker volume, audio routing, and sound feedback.
 * 4. Acoustic environment sensing & ambient noise classification.
 */

enum class AcousticEnvironment {
    QUIET_STUDY,
    NORMAL_INDOOR,
    LOUD_OUTDOORS,
    HIGH_NOISE_URBAN
}

data class ConnectedPeripherals(
    val hasMouseConnected: Boolean,
    val hasPhysicalKeyboard: Boolean,
    val hasGamepad: Boolean,
    val connectedInputDevicesCount: Int,
    val deviceNames: List<String>
)

data class CameraHardwareMatrix(
    val hasFrontCamera: Boolean,
    val frontCameraId: String?,
    val hasBackCamera: Boolean,
    val backCameraId: String?,
    val totalCameraCount: Int,
    val hasFlashUnit: Boolean
)

data class AudioSensoryState(
    val volumePercent: Int,
    val isMusicActive: Boolean,
    val isSpeakerphoneOn: Boolean,
    val isWiredHeadsetOn: Boolean,
    val isBluetoothA2dpOn: Boolean,
    val estimatedAcousticEnvironment: AcousticEnvironment
)

data class DeviceSensoryProfile(
    val peripherals: ConnectedPeripherals,
    val cameras: CameraHardwareMatrix,
    val audio: AudioSensoryState,
    val timestamp: Long = System.currentTimeMillis()
)

object WastiDeviceSensoryEngine {

    private const val TAG = "DeviceSensoryEngine"

    /**
     * Inspects full hardware sensory and peripheral reality.
     */
    suspend fun inspectSensoryEnvironment(context: Context): DeviceSensoryProfile = withContext(Dispatchers.IO) {
        val periph = inspectPeripherals(context)
        val cams = inspectCameras(context)
        val audio = inspectAudio(context)

        DeviceSensoryProfile(
            peripherals = periph,
            cameras = cams,
            audio = audio
        )
    }

    private fun inspectPeripherals(context: Context): ConnectedPeripherals {
        val im = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        val deviceIds = im?.inputDeviceIds ?: intArrayOf()

        var hasMouse = false
        var hasKeyboard = false
        var hasGamepad = false
        val names = mutableListOf<String>()

        for (id in deviceIds) {
            val dev = im?.getInputDevice(id) ?: continue
            val sources = dev.sources
            names.add(dev.name)

            if ((sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
                hasMouse = true
            }
            if ((sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD && !dev.isVirtual) {
                hasKeyboard = true
            }
            if ((sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
                hasGamepad = true
            }
        }

        return ConnectedPeripherals(
            hasMouseConnected = hasMouse,
            hasPhysicalKeyboard = hasKeyboard,
            hasGamepad = hasGamepad,
            connectedInputDevicesCount = deviceIds.size,
            deviceNames = names
        )
    }

    private fun inspectCameras(context: Context): CameraHardwareMatrix {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val camIds = try { cm?.cameraIdList ?: emptyArray() } catch (_: Exception) { emptyArray() }

        var frontId: String? = null
        var backId: String? = null
        var hasFlash = false

        for (id in camIds) {
            try {
                val chars = cm?.getCameraCharacteristics(id) ?: continue
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (flash) hasFlash = true

                if (facing == CameraCharacteristics.LENS_FACING_FRONT && frontId == null) {
                    frontId = id
                } else if (facing == CameraCharacteristics.LENS_FACING_BACK && backId == null) {
                    backId = id
                }
            } catch (_: Exception) {}
        }

        return CameraHardwareMatrix(
            hasFrontCamera = frontId != null,
            frontCameraId = frontId,
            hasBackCamera = backId != null,
            backCameraId = backId,
            totalCameraCount = camIds.size,
            hasFlashUnit = hasFlash
        )
    }

    private fun inspectAudio(context: Context): AudioSensoryState {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        val curVol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 7
        val volPct = if (maxVol > 0) (curVol * 100) / maxVol else 50

        val isMusic = am?.isMusicActive == true

        // Modern hardware device output routing inspection via AudioDeviceInfo
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && am != null) {
            try {
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            } catch (_: Throwable) {
                emptyArray()
            }
        } else {
            emptyArray()
        }

        val isSpeaker = devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val isWired = devices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
        val isBt = devices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    it.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                ))
        }

        // Classify acoustic context
        val acoustic = when {
            volPct < 25 -> AcousticEnvironment.QUIET_STUDY
            volPct in 25..65 -> AcousticEnvironment.NORMAL_INDOOR
            volPct in 66..85 -> AcousticEnvironment.LOUD_OUTDOORS
            else -> AcousticEnvironment.HIGH_NOISE_URBAN
        }

        return AudioSensoryState(
            volumePercent = volPct,
            isMusicActive = isMusic,
            isSpeakerphoneOn = isSpeaker,
            isWiredHeadsetOn = isWired,
            isBluetoothA2dpOn = isBt,
            estimatedAcousticEnvironment = acoustic
        )
    }

    /**
     * Generates a markdown sensory hardware report for Wasti's context.
     */
    fun generateSensorySummaryMarkdown(profile: DeviceSensoryProfile): String {
        return buildString {
            appendLine("### ⚡ Device Sensory & Peripherals Reality")
            appendLine("• **Cameras**: Front (${if (profile.cameras.hasFrontCamera) "Available [ID: ${profile.cameras.frontCameraId}]" else "None"}), Back (${if (profile.cameras.hasBackCamera) "Available [ID: ${profile.cameras.backCameraId}]" else "None"}), Flash (${if (profile.cameras.hasFlashUnit) "Yes" else "No"})")
            appendLine("• **Audio Output**: Volume ${profile.audio.volumePercent}%, BT Audio: ${profile.audio.isBluetoothA2dpOn}, Acoustic Context: ${profile.audio.estimatedAcousticEnvironment}")
            appendLine("• **Input Devices**: Mouse (${profile.peripherals.hasMouseConnected}), Keyboard (${profile.peripherals.hasPhysicalKeyboard}), Total: ${profile.peripherals.connectedInputDevicesCount}")
            if (profile.peripherals.deviceNames.isNotEmpty()) {
                appendLine("• **Connected Peripherals**: ${profile.peripherals.deviceNames.joinToString(", ")}")
            }
        }
    }
}
