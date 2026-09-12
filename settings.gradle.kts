pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }

  // Use a current R8 release with AGP 9.1.x. This keeps the existing
  // Android Gradle Plugin and application architecture intact while fixing
  // upstream Kotlin-metadata diagnostics emitted by the bundled shrinker.
  buildscript {
    dependencies {
      classpath("com.android.tools:r8:9.4.14")
    }
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "Wasti OS"

include(":app")
