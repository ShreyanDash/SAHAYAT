plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The Google Services plugin needs app/google-services.json (downloaded from your
// Firebase console). Without it the app still builds and runs, and every backend
// feature reports "Backend not configured" instead of pretending to work.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn("SAHAYAT: app/google-services.json not found - building without Firebase config.")
}

fun prop(name: String, default: String): String =
    (project.findProperty(name) as String?) ?: default

android {
    namespace = "com.sahayat.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sahayat.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "FUNCTIONS_REGION", "\"${prop("sahayat.functionsRegion", "asia-south1")}\"")
        buildConfigField("String", "EMULATOR_HOST", "\"${prop("sahayat.emulatorHost", "10.0.2.2")}\"")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "USE_EMULATOR", prop("sahayat.useEmulator", "false"))
            // Developer TEST ALERT tools are available in debug builds only.
            buildConfigField("boolean", "ENABLE_TEST_ALERTS", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "USE_EMULATOR", "false")
            buildConfigField("boolean", "ENABLE_TEST_ALERTS", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.mlkit.barcode.scanning)
    implementation("com.google.guava:guava:33.3.1-android")

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.messaging)
    implementation(libs.play.services.base)
    // Phone GPS for alert location, and OpenStreetMap maps (free, no API key or billing)
    implementation(libs.play.services.location)
    implementation(libs.osmdroid.android)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
}
