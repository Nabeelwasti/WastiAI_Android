# Real Device & Emulator Verification Specification (P0-33)

## Governing Principle
**NO FAKE VERIFICATION, NO FAKE CAPABILITY, NO FAKE READINESS.**
Device verification cannot be simulated or satisfied by host-runner unit tests or Robolectric JVM mocks. It can strictly be satisfied **ONLY** by actual instrumentation tests running on a physical Android device or Android emulator (`TestTier.DEVICE` or `TestTier.EMULATOR`).

## Test Suite Location
The canonical on-device instrumentation test suite is located at:
`app/src/androidTest/java/com/example/device/RealDeviceAndroidCapabilityTest.kt`

## Required Android Capabilities Verified on Device
1. **Application Identity & Package Contract**:
   - Package ID must strictly match `com.aistudio.wastios.k9v2pz`.
   - ApplicationInfo and versionCode >= 1 on Android API >= 24.
2. **Services & Notifications**:
   - System NotificationManager and notification channel creation (`WastiNotificationManager`).
   - Foreground execution service component registration (`WastiForegroundExecutionService`).
3. **Accessibility & Device Control**:
   - Accessibility service component binding permission (`BIND_ACCESSIBILITY_SERVICE`).
   - Truthful query of `WastiAccessibilityService` registration.
4. **Permission Truth**:
   - Real Android `ContextCompat.checkSelfPermission` checks for Audio, Overlay, Notifications.
   - Declared permissions are never treated as granted without OS verification.
5. **Native Model Execution & Hardware Acceleration**:
   - Real JNI `NativeLlamaBridge.isNativeSupported()` verification against target architecture (`arm64-v8a`, `x86_64`).
   - Hardware acceleration tensor detection on target kernel.
6. **Device Verification Evidence Registration**:
   - Registers authenticated `DeviceExecutionRecord` containing `deviceId`, `deviceModel`, `androidApiLevel`, `isEmulator`, `tier`, and cryptographic signature into `DeviceVerificationEvidenceTracker`.

## Execution Instructions
To run device verification tests on a connected device or emulator:
```bash
# Connect device via ADB or start Android emulator
adb devices

# Execute instrumentation suite
./gradlew connectedAndroidTest

# Alternatively run connected verification check
./gradlew connectedCheck
```

## Behavior When Real Device Is Unavailable
If no physical device or emulator has executed the instrumentation tests:
- `DeviceVerificationEvidenceTracker.hasValidDeviceProof()` strictly returns `false`.
- `ProductionReadinessGate` records `RealDeviceExecutionVerification` as:
  - `isLiveVerified: false`
  - `state: DEVELOPMENT_READY`
  - `notes: "BLOCKED_EXTERNAL_DEVICE: Physical device or emulator instrumentation proof required (never reported without execution)"`
- Overall production readiness status strictly remains **NOT PRODUCTION READY** (`NOT_READY` or `DEVELOPMENT_READY`).
