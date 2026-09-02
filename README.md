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
2. **One Reality**: Strict verification-first distinction across 10 lifecycle states (`DECLARED → CONFIGURED → AVAILABLE → AUTHENTICATED → EXECUTABLE → STARTED → COMPLETED → OBSERVED → VERIFIED → TRUSTED → LEARNED`).
3. **One Execution Fabric**: Every action (files, terminal, network, device automation, self-repair) passes through `UnifiedExecutionFabric`.
4. **Autonomous Skill Evolution**: Unknown tasks trigger dynamic capability discovery, sandboxed WASM synthesis, and verified skill persistence.
5. **Bounded Self-Healing**: Automated diagnosis, repair strategies, rollback snapshots, and bounded retries without infinite loops.

---

## ✨ Features & Capabilities

- 🧠 **Universal Autonomous Runtime**: Multi-provider AI reasoning (Google Gemini, Groq, OpenAI, Local Engines) with automated fallback and health tracking.
- 💻 **WRE (Wasti Runtime Environment)**: Native terminal pipeline, process isolation, package manager, and sandboxed execution.
- 🎙️ **Multi-Engine Voice & Wake Word**: Offline Vosk wake-word listening, Android TextToSpeech, and ElevenLabs neural audio.
- 📱 **System Automation & Accessibility**: Screen reading, touch simulation, and device controls via `WastiAccessibilityService`.
- 🗄️ **Persistent Memory & Room DB**: 14-version schema tracking episodic interactions, learned skills, execution audit trails, and vector embeddings.
- 🛡️ **Zero-Leakage Security**: Android Keystore integration, strict CORS/HMAC webhook verification, and workspace containment.

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

---

## 📜 Contributing
Please read [CONTRIBUTING.md](CONTRIBUTING.md) for development guidelines, testing standards, and pull request procedures.
