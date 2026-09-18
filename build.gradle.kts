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

// Coverage: each module reports its own debug-variant coverage directly
// (./gradlew :core:koverHtmlReportDebug :app:koverHtmlReportDebug, same for XML) — Kover's own
// unqualified koverHtmlReport/koverXmlReport tasks always aggregate every build variant, which
// would force a release compile that no test ever exercises (:automotive is excluded entirely,
// it has no tests either way), so the variant-qualified tasks are used instead of a root-level
// merged report.
subprojects {
    tasks.withType<Test> {
        // MockK mocks final Kotlin classes via a self-attaching Java agent — needs these on
        // JDK 17+ for the attach API to work reliably in the Gradle test JVM.
        jvmArgs("-XX:+EnableDynamicAgentLoading", "-Djdk.attach.allowAttachSelf=true")
    }
}
