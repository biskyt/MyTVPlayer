plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // Test helpers the app's tests share (RegexPortability).
    `java-test-fixtures`
    alias(libs.plugins.animalsniffer)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    // The Java APIs of Android at the app's minSdk, for Animal Sniffer (below).
    signature("com.toasttab.android:gummy-bears-api-${libs.versions.minSdk.get()}:${libs.versions.gummyBears.get()}@signature")
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.okhttp)
    // Android ships org.xmlpull in the framework; kxml2 provides the same API for JVM tests.
    compileOnly(libs.kxml2)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kxml2)
    testFixturesImplementation(libs.junit)
}

// Core runs on Android, but lint does not check API levels in a plain JVM module. Check core's classes against the
// Java APIs of Android at the app's minSdk: LocalDate.EPOCH (Android 14) once crashed older TVs (issue 94).
// It runs with core's tests, so CI and local test runs both check it.
animalsniffer {
    sourceSets = listOf(project.sourceSets.main.get())
}
tasks.test { dependsOn(tasks.named("animalsnifferMain")) }
