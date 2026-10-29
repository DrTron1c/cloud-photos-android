plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "io.github.drtron1c.cloudphotos"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.drtron1c.cloudphotos"
        minSdk = 30
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // On-device tests run against a shrunk build identical to release (plus the Kotlin
    // library the test framework needs).
    testBuildType = "releaseTest"

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            testProguardFiles("test-proguard-rules.pro")
            // Phones and tablets are arm64: keeps the AI and VPN native libraries from bloating the APK.
            ndk { abiFilters += listOf("arm64-v8a") }
            // Left unsigned here: CI signs it with the private key kept in GitHub's secret storage.
        }
        debug {
            // Android's throwaway debug key (never used for the real app).
            signingConfig = signingConfigs.getByName("debug")
        }
        create("releaseTest") {
            initWith(getByName("release"))
            proguardFile("test-app-rules.pro")
            matchingFallbacks += listOf("release")
            // Also x86_64 for the test emulator. Signed with the throwaway debug key: it only
            // ever runs on the test emulator.
            ndk { abiFilters.clear(); abiFilters += listOf("arm64-v8a", "x86_64") }
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/previous-compilation-data.bin")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/launcherTemplate"))
}

// The companion-app template (see :launcher) travels inside Cloud Photos as an asset.
val copyLauncherTemplate = tasks.register<Copy>("copyLauncherTemplate") {
    dependsOn(":launcher:assembleRelease")
    from(rootProject.layout.projectDirectory.file("launcher/build/outputs/apk/release/launcher-release-unsigned.apk"))
    into(layout.buildDirectory.dir("generated/launcherTemplate"))
    rename { "launcher-template.apk" }
}
tasks.named("preBuild") { dependsOn(copyLauncherTemplate) }
tasks.withType<Test>().configureEach { dependsOn(copyLauncherTemplate) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("io.coil-kt.coil3:coil-video:3.0.4")
    implementation("me.saket.telephoto:zoomable-image-coil3:0.14.0")
    implementation("me.saket.telephoto:flick:0.14.0")
    implementation("me.saket.telephoto:zoomable:0.14.0")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")

    // Signs the companion app on the device.
    implementation("com.android.tools.build:apksig:8.7.3")

    // Proton VPN over WireGuard (the official WireGuard library).
    implementation("com.wireguard.android:tunnel:1.0.20230706")

    // On-device AI search: CLIP-style model run with ONNX Runtime.
    implementation(project(":aicore"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    "releaseTestImplementation"("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
