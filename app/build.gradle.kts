import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
}

// Machine-local config read from local.properties (git-ignored, never committed).
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Debug-only: talk to Anthropic directly with a dev key, for fast iteration.
val anthropicApiKey: String = localProperties.getProperty("ANTHROPIC_API_KEY", "")

// Release: talk to a backend proxy that holds the real Anthropic key server-side.
// The proxy URL is not a secret (it's just an endpoint). The app token below is a
// stopgap for a private deployment; production should present a runtime credential
// (Play Integrity / user session), NOT a compiled-in token. See proxy/README.md.
val aireProxyUrl: String = localProperties.getProperty("AIRE_PROXY_URL", "")
val aireAppToken: String = localProperties.getProperty("AIRE_APP_TOKEN", "")

android {
    namespace = "com.aire"
    compileSdk = 35
    // Pin to an installed build-tools version so no SDK download is needed.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.aire"
        minSdk = 26
        targetSdk = 35
        versionCode = project.findProperty("versionCode")?.toString()?.toInt() ?: 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // Direct mode: dev key from local.properties. Convenient, and only ever
            // in debug builds you run yourself — never shipped.
            buildConfigField("boolean", "USE_PROXY", "false")
            buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicApiKey\"")
            buildConfigField("String", "AIRE_PROXY_URL", "\"\"")
            buildConfigField("String", "AIRE_APP_TOKEN", "\"\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Proxy mode: NO Anthropic key compiled into the app. The app presents
            // its own token; the proxy validates it and injects the real key.
            buildConfigField("boolean", "USE_PROXY", "true")
            buildConfigField("String", "ANTHROPIC_API_KEY", "\"\"")
            buildConfigField("String", "AIRE_PROXY_URL", "\"$aireProxyUrl\"")
            buildConfigField("String", "AIRE_APP_TOKEN", "\"$aireAppToken\"")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // The Anthropic SDK uses java.time and other desugarable APIs.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/FastDoubleParser-LICENSE"
        }
    }
}

dependencies {
    // Force secure versions of transitive dependencies (Socket Alerts)
    constraints {
        implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")
        implementation("com.fasterxml.jackson.core:jackson-core:2.22.2")
        implementation("org.apache.httpcomponents.core5:httpcore5:5.4.3")
        implementation("org.apache.httpcomponents.core5:httpcore5-h2:5.4.3")
        implementation("org.apache.httpcomponents.client5:httpclient5:5.6.4")
        implementation("com.github.victools:jsonschema-generator:5.0.0")
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.anthropic.java)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Compose UI
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.extensions)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Location
    implementation(libs.play.services.location)

    // Security
    implementation(libs.androidx.security.crypto)
}
