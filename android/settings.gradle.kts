pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.13.2"
        kotlin("jvm") version "2.2.20"
        kotlin("android") version "2.2.20"
        id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HealthApp"
include(":core")

// Pure JVM verification must not require an installed Android SDK.
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
}
