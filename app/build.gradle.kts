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

// --- Runtime Secret Injection (fix: CI reported secrets as "injected" but ---
// --- none ever reached a real installed APK) --------------------------------
// The CI workflow (.github/workflows/build-apk.yml) writes every configured
// GitHub Actions secret into a `.env` file at the REPOSITORY ROOT. At runtime,
// CredentialRegistry.getBuildConfigString() looks up secrets via reflection
// into BuildConfig.<KEY_NAME> fields — but nothing in this module previously
// generated those BuildConfig fields, so every lookup returned null on a real
// device regardless of what the CI log said. This loader reads that root
// .env (falling back to real OS environment variables for local `./gradlew`
// runs) so every key below can be exposed as a genuine BuildConfig field.
// This is additive only: it does not touch or reduce the user's own
// EncryptedSharedPreferences secret vault, which remains fully intact as the
// fallback path a user can use to add or override any key by hand.
val wastiSecretsEnvFile = rootProject.file(".env")
val wastiSecretsEnvProps = Properties().apply {
  if (wastiSecretsEnvFile.exists()) {
    wastiSecretsEnvFile.inputStream().use { load(it) }
  }
}
fun wastiSecret(key: String): String {
  val raw = wastiSecretsEnvProps.getProperty(key) ?: System.getenv(key) ?: ""
  return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "")
}
// Canonical key names — kept in exact sync with every `inject_secret "KEY"`
// call in .github/workflows/build-apk.yml. If a new secret is added to the
// workflow, add its exact key name here too so it actually reaches the app.
val wastiSecretKeys = listOf(
  "ALLOWED_GITHUB_REPOS", "ALLOWED_ORIGINS", "ALLOWED_PATCH_BRANCHES", "ANTHROPIC_API_KEY",
  "BACKEND_GEMINI_KEY", "BACKEND_GITHUB_PAT", "BACKEND_GROQ_VOICE", "BREVO_API_KEY",
  "BREVO_MCP_SERVER_API_KEY", "BYTEZ_API_KEY", "CANVA_ACCESS_TOKEN", "CANVA_CLIENT_ID",
  "CANVA_CLIENT_SECRET", "CLOUDFLARE_ACCOUNT_ID", "CLOUDFLARE_API_KEY", "DEEPSEEK_API_KEY",
  "DISCORD_BOT_ID", "DISCORD_BOT_KEY", "DRIVE_CLIENT_ID", "DRIVE_CLIENT_SECRET",
  "ELEVENLABS_API_KEY", "GEMINI_API_KEY", "GMAIL_APP_PASSWORD", "GMAIL_OAUTH_REFRESH_TOKEN",
  "GMAIL_OAUTH_TOKEN", "GMAIL_SENDER_EMAIL", "GOOGLE_ANDROID_CLIENT_ID", "GOOGLE_API_KEY",
  "GOOGLE_OAUTH_ACCESS_TOKEN", "GOOGLE_REFRESH_TOKEN", "GOOGLE_SEARCH_API_KEY", "GOOGLE_SEARCH_CX",
  "GOOGLE_WEB_CLIENT_ID", "GROQ_API_KEY", "HUBSPOT_CONNECTION_ID", "HUGGINGFACE_ACCESS_TOKEN",
  "LINKEDIN_ACCESS_TOKEN", "LINKEDIN_AUTHOR_URN", "LINKEDIN_CLIENT_ID", "LINKEDIN_CLIENT_SECRET",
  "LINKEDIN_OAUTH_REFRESH_TOKEN", "LINKEDIN_OAUTH_TOKEN", "LINKEDIN_REFRESH_TOKEN", "LOCAL_LLM_URL",
  "NOTION_CONNECTION_ID", "OPENAI_API_KEY", "OPENROUTER_API_KEY", "OUTREACH_APPROVAL_TOKEN",
  "PUBLIC_API_BASE_URL", "PUBLIC_GOOGLE_ANDROID_CLIENT_ID", "PUBLIC_GOOGLE_WEB_CLIENT_ID", "SEARCH_API_KEY",
  "SLACK_DOMAIN", "STRIPE_PUBLISHABLE_KEY", "STRIPE_SANDBOX_RESTRICTED_KEY_TOKEN", "STRIPE_SECRET_KEY",
  "STRIPE_WEBHOOK_SECRET", "UNSPLASH_ACCESS_KEY", "UNSPLASH_APP_ID", "UNSPLASH_SECRET_KEY",
  "UPWORK_OAUTH_CLIENT_ID", "UPWORK_OAUTH_CLIENT_SECRET", "UPWORK_RSS_CUSTOM_URL", "WASTI_BACKEND_AUTH_SECRET",
  "WASTI_BACKEND_URL", "WASTI_GIT_FINE_GRAINED_PAT", "WASTI_GIT_PAT", "XAI_API_KEY",
  "ZAPIER_CONNECT_TOKEN", "ZAPIER_MCP_SHARE_LINK"
)

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

    // Expose every CI-injected secret (see wastiSecretKeys above) as a real
    // BuildConfig.<KEY> string field so CredentialRegistry.getBuildConfigString()
    // can actually find it on a real installed device. Resolves to an empty
    // string when a given secret isn't configured for this build;
    // CredentialRegistry already treats blank/placeholder values as "not set"
    // and falls through to other sources, so this never breaks anything that
    // previously worked.
    wastiSecretKeys.forEach { key ->
      buildConfigField("String", key, "\"${wastiSecret(key)}\"")
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

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects. These files live at the
// REPOSITORY ROOT (one directory above this module, where the CI workflow
// writes them), so the path is relative to app/. Fix: the paths previously
// pointed inside app/ (where no such file ever existed), and
// `ignoreList.add(".*")` was a regex that matches every possible key name,
// silently excluding 100% of secrets from this plugin's own placeholder/
// resource generation — both removed/corrected below. (BuildConfig field
// generation for CredentialRegistry is handled separately above, since this
// plugin only generates manifest placeholders and string resources.)
secrets {
  propertiesFileName = "../.env"
  defaultPropertiesFileName = "../.env.example"
  ignoreList.add("sdk.dir")
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
