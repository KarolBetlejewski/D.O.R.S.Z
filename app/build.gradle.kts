import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/*
 * Klucz aplikacji DJI (developer.dji.com) — przypisany do applicationId.
 * Źródła w kolejności: DJI_API_KEY w local.properties (nie trafia do gita),
 * potem właściwość Gradle -PDJI_API_KEY=... lub ~/.gradle/gradle.properties.
 */
val djiApiKey: String = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText.orNull
    ?.let { text -> Properties().apply { load(StringReader(text)) }.getProperty("DJI_API_KEY") }
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: providers.gradleProperty("DJI_API_KEY").orNull?.trim()
    ?: ""

if (djiApiKey.isEmpty()) {
    logger.warn("w: DJI_API_KEY nie jest ustawiony — rejestracja MSDK się nie powiedzie. Dodaj DJI_API_KEY=<klucz> do local.properties.")
}

android {
    namespace = "com.example.djicontroller"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.djicontroller"
        minSdk = 24
        // Jak w przykładzie DJI (ANDROID_TARGET_SDK_VERSION=35): MSDK 5.18.0 jest testowane z targetSdk 35,
        // a zachowanie zamkniętych bibliotek przy wyższym targetSdk nie jest przez DJI deklarowane.
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["API_KEY"] = djiApiKey

        ndk {
            // MSDK V5 dostarcza biblioteki natywne tylko dla arm64-v8a.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // Odpowiednik android:extractNativeLibs="true" z manifestu przykładu DJI.
            useLegacyPackaging = true
            // DJI MSDK i OpenCV dostarczają własne libc++_shared.so. Scalanie bibliotek natywnych
            // (mergeDebugNativeLibs) odbywa się PRZED filtrem abiFilters, więc konflikt trzeba
            // rozstrzygnąć dla każdego ABI, nie tylko arm64-v8a (przykład DJI: arm64-v8a + armeabi-v7a).
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/x86/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
            )
            // Lista doNotStrip z android-sdk-v5-sample/build.gradle — tych bibliotek nie wolno strippować.
            keepDebugSymbols += setOf(
                "*/*/libconstants.so",
                "*/*/libdji_innertools.so",
                "*/*/libdjibase.so",
                "*/*/libDJICSDKCommon.so",
                "*/*/libDJIFlySafeCore-CSDK.so",
                "*/*/libdjifs_jni-CSDK.so",
                "*/*/libDJIRegister.so",
                "*/*/libdjisdk_jni.so",
                "*/*/libDJIUpgradeCore.so",
                "*/*/libDJIUpgradeJNI.so",
                "*/*/libDJIWaypointV2Core-CSDK.so",
                "*/*/libdjiwpv2-CSDK.so",
                "*/*/libFlightRecordEngine.so",
                "*/*/libvideo-framing.so",
                "*/*/libwaes.so",
                "*/*/libagora-rtsa-sdk.so",
                "*/*/libc++.so",
                "*/*/libc++_shared.so",
                "*/*/libmrtc_28181.so",
                "*/*/libmrtc_agora.so",
                "*/*/libmrtc_core.so",
                "*/*/libmrtc_core_jni.so",
                "*/*/libmrtc_data.so",
                "*/*/libmrtc_log.so",
                "*/*/libmrtc_onvif.so",
                "*/*/libmrtc_rtmp.so",
                "*/*/libmrtc_rtsp.so",
                // WPMZ SDK: przykład DJI nie wymienia jego bibliotek; wzorzec obejmuje je niezależnie
                // od dokładnej nazwy (sprawdź: unzip -l app-debug.apk | grep lib/).
                "*/*/libwpmz*.so",
            )
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":msdk-adapter"))
    implementation(project(":vision"))
    implementation(project(":station"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maplibre.android)

    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core")))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
