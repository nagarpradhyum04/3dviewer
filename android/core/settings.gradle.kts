// The core module is a standalone Gradle build (pure Kotlin/JVM, no Android dependencies) so its
// logic can be compiled and unit-tested without the Android SDK: `gradle -p core test`.
// The app consumes it through `includeBuild("core")` in ../settings.gradle.kts.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "core"
