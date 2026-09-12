import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import org.gradle.api.tasks.testing.Test
import java.time.Duration
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

// Wasti OS secret boundary:
// - Long-lived/private credentials MUST NOT be compiled into the Android APK.
// - User credentials are stored in CredentialRegistry's encrypted vault.
// - Server-only credentials stay on the controlled backend/execution boundary.
// - Only explicitly public configuration is allowed in BuildConfig.
val isTestTaskExecution = gradle.startParameter.taskNames.any {
  it.contains("test", ignoreCase = true) || it.contains("check", ignoreCase = true)
}

fun resolvePublicConfigWithFallback(key: String, safeStaticFallback: String): String {
  if (isTestTaskExecution) {
    return when (key) {
      "PUBLIC_API_BASE_URL" -> "https://mock.wasti.internal"
      "WASTI_BACKEND_URL" -> "http://127.0.0.1:8080"
      "BUILD_ENVIRONMENT" -> "test"
      else -> safeStaticFallback
    }
  }

  val envVal = System.getenv(key)
  if (!envVal.isNullOrBlank()) return envVal

  val propVal = project.findProperty(key) as? String
  if (!propVal.isNullOrBlank()) return propVal

  val envFile = rootProject.file(".env")
  if (envFile.exists()) {
    try {
      for (line in envFile.readLines()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#") || !trimmed.contains("=")) continue
        val parts = trimmed.split("=", limit = 2)
        if (parts[0].trim() == key) {
          val v = parts[1].trim().trim('"', '\'')
          if (v.isNotBlank()) return v
        }
      }
    } catch (_: Throwable) { /* ignore */ }
  }

  return safeStaticFallback
}

val activeEnvironment = if (isTestTaskExecution) "test" else (System.getenv("BUILD_ENVIRONMENT") ?: "development")

val wastiPublicConfig = mapOf(
  "PUBLIC_API_BASE_URL" to resolvePublicConfigWithFallback("PUBLIC_API_BASE_URL", "https://api.wasti.ai"),
  "PUBLIC_GOOGLE_WEB_CLIENT_ID" to resolvePublicConfigWithFallback("PUBLIC_GOOGLE_WEB_CLIENT_ID", "wasti-mock-web-client-id.apps.googleusercontent.com"),
  "PUBLIC_GOOGLE_ANDROID_CLIENT_ID" to resolvePublicConfigWithFallback("PUBLIC_GOOGLE_ANDROID_CLIENT_ID", "wasti-mock-android-client-id.apps.googleusercontent.com"),
  "WASTI_BACKEND_URL" to resolvePublicConfigWithFallback("WASTI_BACKEND_URL", resolvePublicConfigWithFallback("PUBLIC_API_BASE_URL", "https://api.wasti.ai")),
  "BUILD_ENVIRONMENT" to activeEnvironment
)

fun wastiPublicValue(value: String): String =
  value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "")

android {
  namespace = "com.example"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.aistudio.wastios.k9v2pz"
    minSdk = 24
    targetSdk = 36
    versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
    versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
    multiDexEnabled = true
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    wastiPublicConfig.forEach { (key, value) ->
      buildConfigField("String", key, "\"${wastiPublicValue(value)}\"")
    }
  }

  val releaseKeystorePath = System.getenv("KEYSTORE_PATH")
  val releaseStorePassword = System.getenv("STORE_PASSWORD")
  val releaseKeyPassword = System.getenv("KEY_PASSWORD")
  val releaseKeyAlias = System.getenv("KEY_ALIAS") ?: "upload"
  val releaseSigningReady = !releaseKeystorePath.isNullOrBlank() &&
      !releaseStorePassword.isNullOrBlank() &&
      !releaseKeyPassword.isNullOrBlank() &&
      file(releaseKeystorePath).exists()

  signingConfigs {
    if (releaseSigningReady) {
      create("release") {
        storeFile = file(releaseKeystorePath)
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
    val debugKs = file("${rootDir}/debug.keystore")
    if (debugKs.exists()) {
      create("debugConfig") {
        storeFile = debugKs
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
    }
    debug {
      val debugKs = file("${rootDir}/debug.keystore")
      if (debugKs.exists() && signingConfigs.findByName("debugConfig") != null) {
        signingConfig = signingConfigs.getByName("debugConfig")
      }
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  lint {
    abortOnError = false
    checkReleaseBuilds = true
    warningsAsErrors = false
    ignoreTestSources = true
    disable += setOf("MissingTranslation", "ExtraTranslation")
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = false
      isReturnDefaultValues = true
      all { test: Test ->
        test.maxHeapSize = "2g"
        test.jvmArgs("-XX:+UseG1GC", "-Drobolectric.logging=stdout")
        test.systemProperty("ENVIRONMENT", "test")
        test.systemProperty("WASTI_TEST_MODE", "true")
        test.systemProperty("WASTI_ENV", "test")
        test.environment("WASTI_ENV" to "test")
        test.environment("ENVIRONMENT" to "test")
        // CI must report failures, not print thousands of successful test events.
        // Excessive per-test stdout makes the suite slower and obscures hangs.
        test.testLogging {
          events("skipped", "failed")
          showStandardStreams = false
          exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
          showExceptions = true
          showCauses = true
          showStackTraces = true
        }
        // This suite contains process-wide singletons, embedded servers, Room state,
        // and global emergency-stop state. Keep JVM test classes isolated by process
        // order rather than introducing cross-class races in CI.
        test.maxParallelForks = 1
        // Stop at the first real test failure so CI never burns the remaining budget
        // after the suite has already established a failing state.
        test.failFast = true
        // Short CI safety ceiling. This applies only to JVM test execution in CI;
        // it does not restrict Wasti OS runtime execution on a real device.
        test.timeout.set(Duration.ofMinutes(4))
      }
    }
  }

  packaging {
    resources {
      excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/DEPENDENCIES")
    }
    jniLibs {
      keepDebugSymbols += setOf("**/libjnidispatch.so", "**/libwasti_ai_native.so", "**/libllama.so")
    }
  }
}

java {
  toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add(".*")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  implementation(libs.firebase.analytics)
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.auth)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)
  implementation(libs.firebase.appcheck.playintegrity)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  implementation("androidx.work:work-runtime-ktx:2.8.1")
  implementation("androidx.appcompat:appcompat:1.6.1")
  implementation("com.alphacephei:vosk-android:0.3.47")
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}