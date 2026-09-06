# ⚡ Wasti AI OS — Autonomous Executive Computing Architecture

[![Android APK Build](https://github.com/Nabeelwasti/WastiAI_Android/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Nabeelwasti/WastiAI_Android/actions/workflows/build-apk.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-7F52FF.svg?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-M3-4285F4.svg?style=flat&logo=android&logoColor=white)](https://developer.android.com/jetpack/compose)

> *"One Brain. One Reality. One Execution Fabric. One Memory. Many Bodies.*  
> *One Command. Infinite Capability. Eternal Evolution.*  
> *Faisla aapka. Mehnat Wasti ki. Saboot reality ka."*

**Wasti AI OS** is an autonomous executive computing operating system built on Android with Jetpack Compose, Kotlin coroutines, Room persistence, and a multi-body execution architecture. It transforms natural language intent into verified, real-world computational execution.

---

## 🏛️ Core Architecture

```
HUMAN INTENT
     ↓
SEMANTIC INTERPRETATION (CapabilityPlanner)
     ↓
REALITY ASSESSMENT (RealityAuditEngine)
     ↓
CAPABILITY DISCOVERY (CapabilityDiscoveryEngine)
     ↓
TASK DECOMPOSITION & DAG PLANNING
     ↓
UNIVERSAL AUTONOMOUS EXECUTION LOOP
     ↓
UNIFIED EXECUTION FABRIC (WRE / WASM / Network / System)
     ↓
OBSERVATION & VERIFICATION (VerificationEngine)
     ↓
SELF-HEALING & RECOVERY (RecoveryPlanner)
     ↓
MEMORY RECORDING & SKILL EVOLUTION (AutonomousSkillEvolutionEngine)
```

### Key Pillars
1. **One Brain**: Single canonical cognitive pipeline eliminating competing routing authorities.
2. **One Reality**: Strict verification-first distinction across 11 lifecycle states (`DECLARED → CONFIGURED → AVAILABLE → AUTHENTICATED → EXECUTABLE → STARTED → COMPLETED → OBSERVED → VERIFIED → TRUSTED → LEARNED`).
3. **One Execution Fabric**: Every action (files, terminal, network, device automation, self-repair) passes through `UnifiedExecutionFabric`.
4. **Autonomous Skill Evolution**: Unknown tasks trigger dynamic capability discovery, sandboxed WASM synthesis, and verified skill persistence.
5. **Bounded Self-Healing**: Automated diagnosis, repair strategies, rollback snapshots, and bounded retries without infinite loops.

---

## ⚖️ The Eternal Manifesto

> **NO FAKE SUCCESS, NO FAKE VERIFICATION, NO FAKE CAPABILITY, NO FAKE READINESS.**  
> *Wasti never mistakes ambition for evidence.*

Every capability, execution result, and readiness state must be established by real, objective evidence. Heuristic fallbacks, micro-interpreters, and simulated components are truthfully identified and never represented as neural weights or operational native engines without live proof.

---

## ✨ Features & Capabilities

- 🧠 **Universal Autonomous Runtime**: Multi-provider AI reasoning (Google Gemini, Groq, OpenAI, Local Engines) with capability-aware routing, observable fallback tracking, and health monitoring.
- 💻 **WRE (Wasti Runtime Environment)**: Native terminal pipeline, process isolation, workspace containment, disk mutation verification, and Room SQLite audit logging.
- 📦 **WASM Sandbox Runtime**: Embedded bytecode micro-interpreter for integer evaluation and sandboxed execution, with truthful gating for native WASI engine availability.
- 🎙️ **Multi-Engine Voice & Wake Word**: Offline Vosk wake-word listening, Android TextToSpeech, ElevenLabs neural audio, and observable in-memory event buffering.
- 📱 **System Automation & Accessibility**: Screen reading, touch simulation, and device controls via `WastiAccessibilityService`, guarded by explicit user consent policies.
- 🗄️ **Persistent Memory & Room DB**: 14-version schema tracking episodic interactions, learned skills, execution audit trails, and vector embeddings.
- 🛡️ **Zero-Leakage Security**: Hardware-backed Android Keystore AES-256-GCM encryption, scoped backend authorization, emergency stop active cancellation, and self-modification safety bounding.

---

## 🛠️ Build & Development

### Compile Android Project
```bash
gradle :app:assembleDebug
```

### Run Full Test Suite
```bash
gradle :app:testDebugUnitTest
```

### Run Backend Companion Test Suite
```bash
node --test backend/test_backend.js
```

---

## 📜 Contributing
Please read [CONTRIBUTING.md](CONTRIBUTING.md) for development guidelines, testing standards, and pull request procedures.
