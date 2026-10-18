plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Bump both on every change that goes into the APK (see CLAUDE.md "Version Management Workflow").
val appVersionCode = 54
val appVersionName = "0.12.3-new-package, Oct 18"

// The in-app updater compares semantic versions that match the GitHub release tags:
//   stable (built from main):    X.Y.Z
//   beta   (built from develop): X.Y.Z-beta.<versionCode>
// CI passes -PreleaseChannel=stable for main; every other build (local, develop) is a beta.
val appCoreVersion = Regex("""\d+\.\d+\.\d+""").find(appVersionName)!!.value
val appReleaseChannel = (findProperty("releaseChannel") as String?) ?: "beta"
val appUpdateVersion = if (appReleaseChannel == "stable") appCoreVersion else "$appCoreVersion-beta.$appVersionCode"

android {
    namespace = "com.example.flash"
    compileSdk = 37

    defaultConfig {
        // The code's package (namespace) stays com.example.flash; only the app's identity changes
        applicationId = "com.dimax.flash"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_VERSION", "\"$appUpdateVersion\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
        // Same opt-ins as Impulse (the copied update UI uses the Expressive APIs in many places)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi"
        )
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // Material3 (pinned alpha — outside BOM)
    implementation(libs.compose.material3)

    // Navigation
    implementation(libs.navigation.compose)
    implementation(libs.activity.compose)

    // Lifecycle / ViewModel
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Ktor server
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.core)

    // Ktor client
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.serialization.json)

    // Images
    implementation(libs.coil.compose)

    // Persistence
    implementation(libs.datastore.preferences)

    // Logging
    implementation(libs.slf4jApi)
    implementation(libs.logbackAndroid)

    // Liquid Glass
    implementation(libs.backdrop)
    implementation(libs.kyant.shapes)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Camera
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.register("printVersionName") {
    doLast { println(appVersionName) }
}

// The GitHub release tag for this build (X.Y.Z or X.Y.Z-beta.N), used by CI
tasks.register("printUpdateTag") {
    doLast { println(appUpdateVersion) }
}