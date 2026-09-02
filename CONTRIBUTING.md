# Contributing to Wasti AI OS

Thank you for contributing to **Wasti AI OS** — the autonomous computing architecture designed to transform human intent into verified real-world execution.

## 🏛️ Architectural Doctrine & Core Principles

All contributions must strictly adhere to **The Eternal Manifesto**:

1. **One Brain (The First Law)**: Every request enters the single canonical pipeline (`INTENT → SEMANTIC INTERPRETATION → REALITY ASSESSMENT → CAPABILITY DISCOVERY → EXECUTION → OBSERVATION → VERIFICATION → LEARNING → EVOLUTION`). No parallel or competing orchestrators.
2. **One Reality (The Second Law)**: Truthful state differentiation across the 10 stages (`DECLARED → CONFIGURED → AVAILABLE → AUTHENTICATED → EXECUTABLE → STARTED → COMPLETED → OBSERVED → VERIFIED → TRUSTED → LEARNED`). Never fabricate success.
3. **One Execution Fabric (The Third Law)**: Every action (code, files, WRE, network, APIs, self-repair) passes through `UnifiedExecutionFabric`.
4. **Verification-First (The Eleventh Law)**: Every capability must define an objective verification criterion.
5. **Zero-Leakage Security**: No hardcoded API keys, passwords, or tokens in source files or release artifacts. Use Android Keystore, `EncryptedSharedPreferences`, or the platform Secrets panel.

---

## 🛠️ Development Setup

### Prerequisites
- **JDK**: Java 17 or higher
- **Android SDK**: Compile SDK 34, Target SDK 34, Min SDK 24
- **Node.js**: v18+ (for headless backend companion services in `/backend`)
- **Gradle**: 8.4+ with Kotlin DSL (`build.gradle.kts`)

### Building the Android App
```bash
# Build Debug APK
gradle :app:assembleDebug

# Run Robolectric & Unit Test Suites
gradle :app:testDebugUnitTest
```

### Running Backend Services
```bash
cd backend
npm install
npm run start
```

---

## 🧪 Testing Standards & Release Gates

- **Unit & Robolectric Tests**: Must pass 100% of test suites before any pull request is merged (`gradle :app:testDebugUnitTest`).
- **Fail-Closed Security**: Any unauthorized origin or missing signature (e.g. Stripe webhook) must fail closed.
- **Self-Healing Loop**: If adding recovery routines, ensure retries are strictly bounded with timeout checks and loop prevention.

---

## 📜 Code Style & Conventions
- **Language**: 100% idiomatic Kotlin with coroutines and structured concurrency (`viewModelScope`, `flow`).
- **UI**: Jetpack Compose adhering to Material Design 3 (M3).
- **Naming**: Descriptive snake_case for UI `testTag` modifiers and resource IDs.
