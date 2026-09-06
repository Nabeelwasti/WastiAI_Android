# Wasti AI OS - Production ProGuard & R8 Optimization and Keep Rules
# Preserves required classes across R8 minification, tree-shaking, and obfuscation.

# 1. Line Numbers & Stack Traces for Production Crash Diagnostics
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# 2. Core Application & Component Architecture
-keep class com.example.WastiApplication { *; }
-keep class com.example.data.** { *; }
-keep class com.example.domain.** { *; }
-keep class com.example.ui.** { *; }

# 3. Android Components, Services, & Receivers
-keep class com.example.service.** { *; }
-keep class com.example.assistant.** { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# 4. AI Engine, Model Providers, & Autonomous Runtime
-keep class com.example.data.ai.provider.** { *; }
-keep interface com.example.data.ai.provider.AIProvider { *; }
-keep class com.example.data.ai.engine.** { *; }
-keep class com.example.data.ai.model.** { *; }
-keep class com.example.data.agent.runtime.** { *; }

# 5. JNI Native Bridges & C++ Runtime Interop
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.example.data.ai.runtime.NativeLlamaBridge {
    native <methods>;
    *;
}
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# 6. Room Database Persistence, DAOs, & Entities
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class com.example.data.db.** { *; }
-keep class com.example.data.db.*_Impl { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# 7. Firebase & Google Cloud Infrastructure
-dontwarn com.google.firebase.**
-keep class com.google.firebase.** { *; }
-dontwarn com.google.android.gms.**
-keep class com.google.android.gms.** { *; }
-keep class com.example.data.cloud.** { *; }
-keep class com.example.data.auth.** { *; }

# 8. WorkManager Durable Background Tasks
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# 9. Security, Biometrics, & Hardware Vault
-dontwarn androidx.security.crypto.**
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.biometric.**
-keep class androidx.biometric.** { *; }
-keep class com.example.data.security.** { *; }

# 10. Networking, HTTP, & API Services
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-dontwarn okio.**
-keep class okio.** { *; }
-keep class com.example.data.api.** { *; }
-dontwarn com.sun.net.httpserver.**

# 11. Serialization & JSON Codecs (Moshi & Kotlinx)
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <fields>;
}
-keep class com.squareup.moshi.** { *; }
-dontwarn kotlinx.serialization.**
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
    @kotlinx.serialization.SerialName <fields>;
}

# 12. Coroutines & Concurrency
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# 13. Vosk Speech Recognition
-dontwarn org.vosk.**
-keep class org.vosk.** { *; }

# 14. Jetpack Compose UI Runtime
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# 15. WRE Execution Fabric & Command Engine
-keep class com.example.data.wre.CommandNode** { *; }
-keep class com.example.data.wre.ChainOperator { *; }
-keep class com.example.data.wre.** { *; }
