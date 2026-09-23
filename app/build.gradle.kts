import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import org.gradle.api.tasks.testing.Test
import java.time.Duration

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

val isTestTaskExecution = gradle.startParameter.taskNames.any {
  it.contains("test", ignoreCase = true) || it.contains("check", ignoreCase = true)
}

fun isPlaceholderValue(v: String): Boolean {
  val clean = v.trim().trim('"', '\'')
  val u = clean.uppercase()
  return clean.isBlank() ||
      u in setOf("YOUR_KEY", "MY_KEY", "PLACEHOLDER", "ENTER_KEY_HERE", "NONE", "NULL", "TODO", "CHANGEME", "UNDEFINED", "DUMMY", "FAKE") ||
      clean.startsWith("your_") ||
      clean.startsWith("my_") ||
      clean.startsWith("todo_") ||
      clean.startsWith("changeme_") ||
      clean.startsWith("dummy_") ||
      clean.startsWith("fake_")
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
  if (!envVal.isNullOrBlank() && !isPlaceholderValue(envVal)) return envVal

  val propVal = project.findProperty(key) as? String
  if (!propVal.isNullOrBlank() && !isPlaceholderValue(propVal)) return propVal

  val envFile = rootProject.file(".env")
  if (envFile.exists()) {
    try {
      for (line in envFile.readLines()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#") || !trimmed.contains("=")) continue
        val parts = trimmed.split("=", limit = 2)
        if (parts[0].trim() == key) {
          val v = parts[1].trim().trim('"', '\'')
          if (v.isNotBlank() && !isPlaceholderValue(v)) return v
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

val allTrackedCredentialKeys = listOf(
  "GEMINI_API_KEY", "GROQ_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY",
  "DEEPSEEK_API_KEY", "XAI_API_KEY", "OPENROUTER_API_KEY", "HUGGINGFACE_ACCESS_TOKEN",
  "BYTEZ_API_KEY", "LOCAL_LLM_URL", "PUBLIC_GOOGLE_WEB_CLIENT_ID", "PUBLIC_GOOGLE_ANDROID_CLIENT_ID",
  "GOOGLE_WEB_CLIENT_ID", "GOOGLE_ANDROID_CLIENT_ID", "GOOGLE_API_KEY", "DRIVE_CLIENT_ID",
  "DRIVE_CLIENT_SECRET", "GMAIL_SENDER_EMAIL", "GMAIL_APP_PASSWORD", "GMAIL_OAUTH_TOKEN",
  "GMAIL_OAUTH_REFRESH_TOKEN", "GOOGLE_OAUTH_ACCESS_TOKEN", "GOOGLE_REFRESH_TOKEN",
  "GOOGLE_SEARCH_API_KEY", "SEARCH_API_KEY", "GOOGLE_SEARCH_CX", "STRIPE_SECRET_KEY",
  "STRIPE_PUBLISHABLE_KEY", "STRIPE_WEBHOOK_SECRET", "STRIPE_SANDBOX_RESTRICTED_KEY_TOKEN",
  "BREVO_API_KEY", "BREVO_MCP_SERVER_API_KEY", "HUBSPOT_CONNECTION_ID", "UPWORK_OAUTH_CLIENT_ID",
  "UPWORK_OAUTH_CLIENT_SECRET", "UPWORK_RSS_CUSTOM_URL", "WASTI_GIT_FINE_GRAINED_PAT",
  "WASTI_GIT_PAT", "BACKEND_GITHUB_PAT", "ALLOWED_GITHUB_REPOS", "ALLOWED_PATCH_BRANCHES",
  "CLOUDFLARE_API_KEY", "CLOUDFLARE_ACCOUNT_ID", "UNSPLASH_APP_ID", "UNSPLASH_ACCESS_KEY",
  "UNSPLASH_SECRET_KEY", "CANVA_CLIENT_ID", "CANVA_CLIENT_SECRET", "CANVA_ACCESS_TOKEN",
  "WASTI_BACKEND_AUTH_SECRET", "PUBLIC_API_BASE_URL", "WASTI_BACKEND_URL", "BACKEND_GEMINI_KEY",
  "BACKEND_GROQ_VOICE", "OUTREACH_APPROVAL_TOKEN", "ALLOWED_ORIGINS", "ELEVENLABS_API_KEY",
  "LINKEDIN_CLIENT_ID", "LINKEDIN_CLIENT_SECRET", "LINKEDIN_OAUTH_TOKEN", "LINKEDIN_ACCESS_TOKEN",
  "LINKEDIN_OAUTH_REFRESH_TOKEN", "LINKEDIN_REFRESH_TOKEN", "LINKEDIN_AUTHOR_URN",
  "ZAPIER_CONNECT_TOKEN", "ZAPIER_MCP_SHARE_LINK", "NOTION_CONNECTION_ID", "SLACK_DOMAIN",
  "DISCORD_BOT_ID", "DISCORD_BOT_KEY", "FIREBASE_SA_BASE64"
)

fun wastiPublicValue(value: String): String =
  value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "")

android {
  namespace = "com.example"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.aistudio.wastios.k9v2pz"
    minSdk = 24
    targetSdk = 37
    versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
    versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
    multiDexEnabled = true
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    wastiPublicConfig.forEach { (key, value) ->
      buildConfigField("String", key, "\"${wastiPublicValue(value)}\"")
    }

    allTrackedCredentialKeys.forEach { key ->
      buildConfigField("String", key, "\"\"")
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

  val isReleaseBuild = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true) || it.contains("bundle", ignoreCase = true)
  }

  lint {
    abortOnError = isReleaseBuild
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
        test.testLogging {
          events("skipped", "failed")
          showStandardStreams = false
          exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
          showExceptions = true
          showCauses = true
          showStackTraces = true
        }
        test.addTestListener(object : org.gradle.api.tasks.testing.TestListener {
          override fun beforeSuite(suite: org.gradle.api.tasks.testing.TestDescriptor) {}
          override fun afterSuite(suite: org.gradle.api.tasks.testing.TestDescriptor, result: org.gradle.api.tasks.testing.TestResult) {
            if (suite.parent == null) {
              println("\n========================================================")
              println("  [UNIT TEST SUITE COMPLETE] Total: ${result.testCount} tests executed")
              println("  Passed: ${result.successfulTestCount} | Failed: ${result.failedTestCount} | Skipped: ${result.skippedTestCount}")
              println("========================================================\n")
            } else if (suite.className != null) {
              val status = if (result.failedTestCount > 0) "FAILED" else "PASSED"
              println("  ✔ [TEST SUITE $status] ${suite.className} (${result.testCount} tests, ${result.successfulTestCount} passed)")
            }
          }
          override fun beforeTest(testDescriptor: org.gradle.api.tasks.testing.TestDescriptor) {}
          override fun afterTest(testDescriptor: org.gradle.api.tasks.testing.TestDescriptor, result: org.gradle.api.tasks.testing.TestResult) {
            if (result.resultType == org.gradle.api.tasks.testing.TestResult.ResultType.FAILURE) {
              println("[TEST FAILED] ${testDescriptor.className} -> ${testDescriptor.name}")
              result.exception?.let { exc ->
                println("[TEST ERROR] ${exc.message}")
              }
            }
          }
        })
        test.maxParallelForks = 1
        test.failFast = true
        test.timeout.set(Duration.ofMinutes(12))
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

tasks.matching { it.name.startsWith("assembleRelease") || it.name.startsWith("bundleRelease") }.configureEach {
  doFirst {
    val releaseKeystorePath = System.getenv("KEYSTORE_PATH")
    val releaseStorePassword = System.getenv("STORE_PASSWORD")
    val releaseKeyPassword = System.getenv("KEY_PASSWORD")
    val ready = !releaseKeystorePath.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank() &&
        file(releaseKeystorePath).exists()
    if (!ready) {
      throw GradleException(
        "Production release signing is not configured. Set KEYSTORE_PATH, STORE_PASSWORD, and KEY_PASSWORD and provide the keystore before assembling or bundling release."
      )
    }
  }
}

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
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.appcompat)
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
  ksp(libs.androidx.room.compiler)
  ksp(libs.moshi.kotlin.codegen)
}
