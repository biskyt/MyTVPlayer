plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.kover)
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt.yml"))
        // Besides the default main and test sources: core's test fixtures, and the app's test code shared with the
        // emulator tests.
        source.from("src/testFixtures/kotlin", "src/sharedTest/java")
        parallel = true
    }
}

dependencies {
    kover(project(":core"))
    kover(project(":app"))
}
