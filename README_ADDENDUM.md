# WastiAI Assistant — Addendum

This addendum includes the first set of modules and helpers for full-device control and continuous assistant operation. It does NOT remove any existing features — it only adds optional helpers and skeletons you can enable.

Files added in branch `feat/full-device-control`:
- app/src/main/java/com/example/assistant/PermissionManager.kt
- app/src/main/java/com/example/assistant/SpecialPermissionHelper.kt
- app/src/main/java/com/example/assistant/AssistantForegroundService.kt
- app/src/main/java/com/example/assistant/WastiAccessibilityService.kt
- app/src/main/java/com/example/assistant/WastiNotificationListener.kt
- app/src/main/java/com/example/assistant/BootReceiver.kt
- app/src/main/java/com/example/assistant/MemoryStore.kt
- app/src/main/java/com/example/assistant/ActionExecutor.kt

Manual steps you must perform on-device (important):
1. Enable the Accessibility service: Settings → Accessibility → WastiAccessibilityService
2. Grant Notification access: Settings → Apps & notifications → Special app access → Notification access → Wasti
3. Grant Draw over apps (overlay) permission: Settings → Apps & notifications → Special app access → Display over other apps
4. Grant All files access (if you want full file backup): Settings → Special app access → All files access
5. Grant "Modify system settings" if you want WRITE_SETTINGS actions.

Security & privacy:
- This app will have powerful capabilities. Keep it private and do not distribute these builds.
- The code stores a simple JSON memory file in your app private storage. I will add encrypted storage and server-backed training next.

Next steps I will implement after you confirm:
- Add encrypted token storage and encrypted Room DB for memory (phase 2).
- Add WorkManager sync worker to back up encrypted memory to Google Drive or a backend.
- Add full LLM integration (via backend recommended) and local embedding support.

If you want me to proceed, reply: "Proceed with Phase 2" and provide any OAuth/client info or API provider preferences.
