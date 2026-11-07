/*
 * :vision — detekcja markerów ArUco (OpenCV objdetect) i estymacja pozy (solvePnP),
 * sekcja 5.4. Zależy wyłącznie od :core; klatki dostaje przez CameraPort.
 * OpenCV ma własne libc++_shared.so — konflikt z DJI rozwiązuje pickFirst w :app.
 */
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.djicontroller.vision"
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
    implementation(libs.opencv)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core")))
}
