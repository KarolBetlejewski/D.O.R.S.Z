import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * :station — łącze ze stacją przez Wi-Fi (sekcja 5.7): OkHttp WebSocket, JSON,
 * heartbeat co 1 s, potwierdzenia `ack`. Moduł czysto JVM (OkHttp nie wymaga Androida),
 * dzięki czemu protokół testujemy na JVM z MockWebServer.
 */
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(testFixtures(project(":core")))
}
