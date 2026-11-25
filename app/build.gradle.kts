import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.theprobyte.golfalone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.theprobyte.golfalone"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // A shared, non-secret key so that APKs built locally and in CI can be
    // installed over each other. Use a private key for any store release.
    signingConfigs {
        create("sideload") {
            storeFile = file("golfalone-sideload.keystore")
            storePassword = "android"
            keyAlias = "golfalone"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
}

// Pass -Pshots=<dir> to save UI screenshots from the Robolectric playthrough test.
tasks.withType<Test>().configureEach {
    (project.findProperty("shots") as String?)?.let { systemProperty("golfalone.shots", it) }
    (project.findProperty("audio") as String?)?.let { systemProperty("golfalone.audio", it) }
    (project.findProperty("candidates") as String?)?.let { systemProperty("golfalone.candidates", it) }
    (project.findProperty("pins") as String?)?.let { systemProperty("golfalone.pins", it) }
    // Robolectric tests are independent classes: run them in parallel JVMs.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    maxHeapSize = "2g"
    testLogging { events("passed", "failed"); showStandardStreams = true }
}
