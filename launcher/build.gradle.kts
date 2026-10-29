// A tiny companion app that only opens Cloud Photos. Cloud Photos ships it as a template and,
// on your device, swaps in the name and picture you choose, then signs and installs it — the
// only way Android allows an app to appear with any name and icon you like.
plugins {
    id("com.android.application")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "io.github.drtron1c.cloudphotos.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.drtron1c.cloudphotos.launcher"
        minSdk = 30
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    buildTypes {
        release {
            // Left unsigned and unshrunk on purpose: resources must keep their names so the
            // name and picture can be swapped on the device before it is signed there.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
