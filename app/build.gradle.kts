plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.biskyt.mytvplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.biskyt.mytvplayer"
        minSdk = 28
        targetSdk = 36
        versionCode = 61
        versionName = "1.8.5"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Only the languages the app has words for (AppText.languages): libraries' others would add size for nothing.
    androidResources { localeFilters += listOf("en", "en-rGB", "en-rUS", "es", "pt", "pt-rBR", "fr") }

    // The language is chosen in the app, not only by the device's: Play must install every language, not just the
    // device's (a few hundred KB).
    bundle { language { enableSplit = false } }

    signingConfigs {
        // A shared, public debug key so builds from any machine (or CI) install over each other when sideloading.
        // For distribution, set MYTV_KEYSTORE and MYTV_KEYSTORE_PASSWORD (MYTV_KEY_ALIAS defaults to "mytvplayer" and
        // MYTV_KEY_PASSWORD to the keystore password, as keytool uses one password for both).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val store = System.getenv("MYTV_KEYSTORE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("MYTV_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MYTV_KEY_ALIAS") ?: "mytvplayer"
                keyPassword = System.getenv("MYTV_KEY_PASSWORD") ?: storePassword
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        // TV boxes and phones are ARM. The emulator smoke tests on CI (x86 or x86_64) ask for theirs too:
        // -PemulatorAbi=x86_64; other debug builds stay as devices get them.
        debug {
            providers.gradleProperty("emulatorAbi").orNull?.let { ndk { abiFilters += it } }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Room's exported schemas, so migration tests can open old database versions.
    sourceSets["debug"].assets.srcDir("$projectDir/schemas")

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // A test JVM per two cores, up to 4 (each needs about 1 GB): about a fifth faster on CI's 4 cores. Each JVM
            // has its own app, database and settings.
            it.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            // Store graphics and screenshots (StoreAssetsTest) are only made when asked for: -PstoreAssets
            it.systemProperty("storeAssets", project.hasProperty("storeAssets").toString())
            it.systemProperty("storeLanguage", project.findProperty("storeLanguage")?.toString().orEmpty())
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

// Unit tests run against debug only: they need its extras (Room schemas for migration tests, the Compose test
// activity), and a release run would only repeat them. This also keeps coverage reports (koverXmlReport) to debug.
androidComponents {
    beforeVariants(selector().withBuildType("release")) {
        it.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui.compose)
    // Only for SubtitleView: the Compose video surface draws no subtitles.
    implementation(libs.media3.ui)
    implementation(libs.media3.ffmpeg.decoder)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.paging.compose)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // QR codes for the support links (the core encoder only; no camera or Android parts).
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.paging.testing)
    testImplementation(libs.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)

    // Smoke tests on a real Android (an emulator on CI): what Robolectric's JVM can't show, e.g. ICU regexes.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
