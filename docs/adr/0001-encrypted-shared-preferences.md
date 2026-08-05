# ADR 0001: Encryption at Rest via EncryptedSharedPreferences

## Status
Accepted

## Context
API keys and authentication credentials (e.g., Gemini, OpenAI, Groq, ElevenLabs, Gmail App Passwords) were previously stored in standard unencrypted Android `SharedPreferences` (`wasti_prefs`) and Room SQLite database tables (`SettingEntity`). On rooted or compromised Android devices, unencrypted XML files stored under `/data/data/com.example/shared_prefs/` are vulnerable to unauthorized access and secret exfiltration.

## Decision
We adopt `androidx.security:security-crypto:1.1.0-alpha06` (`EncryptedSharedPreferences`) backed by the Android hardware-backed `MasterKey` (AES256-GCM / AES256-SIV).

Key mechanisms implemented in `CredentialRegistry`:
1. **Isolated Key Generation**: The `MasterKey` is created using `MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()`.
2. **Encrypted Storage Container**: Credentials are created and managed inside `wasti_secure_prefs`.
3. **Transparent 1-Time Migration & Purge**: On credential resolution (`getRawValue`), if a secret is found in legacy unencrypted `wasti_prefs`, it is transparently encrypted and saved into `wasti_secure_prefs`, and immediately removed (purged) from the legacy plaintext `wasti_prefs`.
4. **No Plaintext Fallback in Production**: New credential saves (`saveCredential`) and seed initializations (`seedDefaultCredentialsIfMissing`) write exclusively to `EncryptedSharedPreferences` and purge legacy plaintext instances.

## Consequences
- **Positive**: All credentials stored on device are encrypted at rest using Android Keystore-backed hardware encryption.
- **Positive**: Legacy unencrypted keys are automatically cleaned up on first run without requiring user intervention.
- **Trade-off**: Requires Android 6.0 (API 23+) or higher and introduces a minor initial initialization latency when creating the MasterKey.
