import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val fixedKeystore = providers.environmentVariable("ANDROID_SIGNING_KEYSTORE").orNull
val fixedPassword = providers.environmentVariable("ANDROID_SIGNING_PASSWORD").orNull
val fixedSigning = fixedKeystore != null || fixedPassword != null
if (fixedSigning) {
    require(!fixedKeystore.isNullOrBlank() && !fixedPassword.isNullOrBlank()) {
        "Fixed signing requires both ANDROID_SIGNING_KEYSTORE and ANDROID_SIGNING_PASSWORD"
    }
    require(file(checkNotNull(fixedKeystore)).isFile) { "Fixed signing keystore is unavailable" }
}

android {
    namespace = "com.ny.my.health"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ny.my.health"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        getByName("debug") {
            if (fixedSigning) {
                storeFile = file(checkNotNull(fixedKeystore))
                storePassword = fixedPassword
                keyAlias = "healthapp"
                keyPassword = fixedPassword
                storeType = "PKCS12"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    constraints {
        implementation("com.google.guava:guava:33.7.2-android") {
            because("Fix temporary-file disclosure and native deserialization advisories in Health Connect's transitive Guava")
        }
    }
    implementation(project(":core"))
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.3.0")
    // Account-free Android UI and lifecycle tests, executed on an emulator in CI.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("com.google.android.gms:play-services-auth:21.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
}
