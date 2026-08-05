# ADR 0007: Multilingual Voice Intelligence Platform (Sprint 5)

## Status
Accepted & Implemented

## Context
Following Sprint 4, `Wasti AI` required an extensible **Voice Intelligence Platform** with vendor-agnostic abstractions for text-to-speech (TTS), speech-to-text (STT), and voice call processing across English, Urdu, Roman Urdu, and Punjabi.

## Decision
We implemented `com.example.data.voice`:

1. **`SpeechProvider` Abstraction**:
   - `SpeechProvider` Interface: Generic contract implemented by `ElevenLabsSpeechProvider` and `AndroidTTSSpeechProvider`.
   - Polymorphic synthesis and voice selection without hardcoding voice IDs or vendor SDKs into UI components.

2. **Multilingual Processing & Preprocessing Pipeline**:
   - `VoiceManager`: Central orchestrator integrating `WastiSpeechSanitizer` (cleaning Markdown and code tags) and `WastiUrduLanguageEngine` (converting Roman Urdu phonetics to pure Urdu script).
   - Automatic fallback cascade from high-fidelity cloud models (ElevenLabs) to on-device system TTS (`AndroidTTSSpeechProvider`).

3. **Platform Bus Integration**:
   - Emits `VoiceSessionChanged` events over `WastiEventBus` to notify UI, background services, and agent controllers.

## Consequences
- **Positive**: Vendor lock-in prevented; seamless fallback during offline or quota-exceeded states.
- **Positive**: First-class support for English, Urdu, Roman Urdu, and regional languages.
