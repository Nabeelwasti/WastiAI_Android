# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep class com.example.WastiApplication { *; }
-keep class com.example.data.** { *; }
-keep class com.example.domain.** { *; }
-keep class com.example.ui.** { *; }

# Security & Crypto
-dontwarn androidx.security.crypto.**
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.biometric.**
-keep class androidx.biometric.** { *; }

# Retrofit & OkHttp
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-dontwarn okio.**
-keep class okio.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Moshi JSON Serialization
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <fields>;
}
-keep class com.squareup.moshi.** { *; }
-keep class com.example.data.api.** { *; }
-keep class com.example.data.ai.model.** { *; }

# Room Database
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keep class com.example.data.db.** { *; }

# Embedded HttpServer
-dontwarn com.sun.net.httpserver.**

# Vosk Speech Recognition
-dontwarn org.vosk.**
-keep class org.vosk.** { *; }

# Kotlinx Serialization & Coroutines
-dontwarn kotlinx.coroutines.**
-dontwarn kotlinx.serialization.**
-keepattributes *Annotation*
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
    @kotlinx.serialization.SerialName <fields>;
}

