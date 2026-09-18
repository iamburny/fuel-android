// Top-level build file
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
    id("org.jetbrains.kotlinx.kover") version "0.8.3"
}

// Aggregates :core and :app's unit-test coverage into one report at the root
// (./gradlew koverHtmlReport / koverXmlReport) — :automotive is deliberately excluded, it has no
// tests.
dependencies {
    kover(project(":core"))
    kover(project(":app"))
}
