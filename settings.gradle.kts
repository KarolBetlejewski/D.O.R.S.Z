pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // DJI MSDK V5, WPMZ SDK i OpenCV są publikowane w Maven Central.
        // Celowo bez jcenter() i dl.bintray.com — oba repozytoria nie działają.
        mavenCentral()
    }
}

rootProject.name = "patrol-app"

include(":app")
include(":core")
include(":msdk-adapter")
include(":vision")
include(":station")
