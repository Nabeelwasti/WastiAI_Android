import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
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
fun resolvePublicConfigWithFallback(key: String, safeStaticFallback: String): String {
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

val wastiPublicConfig = mapOf(
  "PUBLIC_API_BASE_URL" to resolvePublicConfigWithFallback("PUBLIC_API_BASE_URL", "https://api.wasti.ai"),
  "PUBLIC_GOOGLE_WEB_CLIENT_ID" to resolvePublicConfigWithFallback("PUBLIC_GOOGLE_WEB_CLIENT_ID", "wasti-mock-web-client-id.apps.googleusercontent.com"),
  "PUBLIC_GOOGLE_ANDROID_CLIENT_ID" to resolvePublicConfigWithFallback("PUBLIC_GOOGLE_ANDROID_CLIENT_ID", "wasti-mock-android-client-id.apps.googleusercontent.com"),
  // A backend URL is an endpoint, not a credential. It is safe to ship so the
  // installed Android process can discover the configured Wasti execution fabric.
  "WASTI_BACKEND_URL" to resolvePublicConfigWithFallback("WASTI_BACKEND_URL", resolvePublicConfigWithFallback("PUBLIC_API_BASE_URL", "https://api.wasti.ai"))
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

    // Public configuration only. Never add API keys, OAuth secrets, PATs,
    // webhook secrets, backend auth tokens, or other privileged credentials here.
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
      if (releaseSigningReady) {
        signingConfig = signingConfigs.getByName("release")
      }
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
      all {
        it.jvmArgs("-XX:+UseG1GC", "-Drobolectric.logging=stdout")
        it.testLogging {
          events("passed", "skipped", "failed", "standardError")
          showStandardStreams = true
          exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
          showExceptions = true
          showCauses = true
          showStackTraces = true
        }
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
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}

// Keep the Secrets Gradle Plugin available for non-secret/public integration
// metadata, but do not allow repository .env values to become APK resources.
// CredentialRegistry is the authoritative encrypted vault for user credentials.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add(".*")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.biometric)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  implementation(libs.firebase.analytics)
  implementation(libs.firebase.firestore)

  // Credential Manager & Auth dependencies for Google Drive & Sign-In:
  implementation(libs.firebase.auth)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  // WorkManager for scheduling background syncs (required for WastiApplication)
  implementation("androidx.work:work-runtime-ktx:2.8.1")
  // AppCompat for AlertDialog used in PermissionManager
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

tasks.register("validateReleaseSigning") {
  doLast {
    val releaseKeystorePath = System.getenv("KEYSTORE_PATH")
    val releaseStorePassword = System.getenv("STORE_PASSWORD")
    val releaseKeyPassword = System.getenv("KEY_PASSWORD")
    if (gradle.startParameter.taskNames.any { it.contains("assembleRelease") || it.contains("bundleRelease") }) {
      if (releaseKeystorePath.isNullOrBlank() || releaseStorePassword.isNullOrBlank() || releaseKeyPassword.isNullOrBlank()) {
        throw GradleException("Release build rejected: KEYSTORE_PATH, STORE_PASSWORD, or KEY_PASSWORD missing.")
      }
    }
  }
}
tasks.matching { it.name.startsWith("assembleRelease") || it.name.startsWith("bundleRelease") }.configureEach {
  dependsOn("validateReleaseSigning")
}
