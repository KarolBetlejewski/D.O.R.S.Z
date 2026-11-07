import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * :core — czysty Kotlin (sekcja 3.3). Zero zależności od android.* i dji.*:
 * modele, porty adapterów, orkiestrator cyklu, nadzór bezpieczeństwa, logowanie.
 * Fake'i portów w src/testFixtures są dostępne dla testów innych modułów przez
 * testImplementation(testFixtures(project(":core"))).
 */
plugins {
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
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
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
