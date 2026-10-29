// On-device AI search core: tokenizer, image preparation and the CLIP-style model wrapper.
// Plain Kotlin/JVM so it is tested on CI against the real model; the app supplies the Android
// build of ONNX Runtime (same Java API) at run time.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    compileOnly("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    testImplementation("org.json:json:20240303")
}

tasks.withType<Test>().configureEach {
    // The model test downloads ~60 MB from Hugging Face once per CI run.
    systemProperty("aicore.cache", layout.buildDirectory.dir("model-cache").get().asFile.absolutePath)
    testLogging { showStandardStreams = true }
}
