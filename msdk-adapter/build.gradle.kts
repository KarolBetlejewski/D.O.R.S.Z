/*
 * :msdk-adapter — JEDYNY moduł zależny od DJI MSDK V5 i WPMZ SDK (sekcja 3.3).
 * Implementuje porty z :core: connection, telemetry, mission, flight, camera, flysafe.
 * Pakowanie bibliotek natywnych (pickFirst, keepDebugSymbols, abiFilters) jest w :app,
 * bo dotyczy finalnego APK.
 */
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.djicontroller.msdk"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core"))

    // Wzorzec: android-sdk-v5-as/dependencies.gradle i android-sdk-v5-sample/build.gradle
    implementation(libs.dji.sdk.aircraft)
    compileOnly(libs.dji.sdk.aircraft.provided)
    runtimeOnly(libs.dji.sdk.network)   // bez tego nie działa rejestracja aplikacji ani sieć MSDK
    implementation(libs.dji.wpmzsdk)    // generowanie i parsowanie KMZ/WPML

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core")))
}
