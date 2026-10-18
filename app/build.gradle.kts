plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Edit these three lines for every release (see CLAUDE.md "Version Management Workflow").
//   appVersionName: X.Y.Z for a stable release, X.Y.Z-beta.N for a beta. This string IS the GitHub
//                   release tag, and the in-app updater compares it, so it must be valid semver.
//   appVersionCode: must increase on every build that is published.
val appVersionCode = 58
val appVersionName = "1.0.0"

android {
    namespace = "com.dimax.flash"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dimax.flash"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_VERSION", "\"$appVersionName\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CI signs with the key stored in GitHub secrets (see .github/workflows/build.yml), which hands
    // the decoded keystore to Gradle through these environment variables. Without them (local and
    // sandbox builds) Android's own auto-generated debug key is used and nothing can be released.
    signingConfigs {
        val keystorePath = System.getenv("KEYSTORE_FILE")
        if (keystorePath != null) {
            create("flash") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("flash")?.let { signingConfig = it }
        }
        release {
            signingConfigs.findByName("flash")?.let { signingConfig = it }
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

