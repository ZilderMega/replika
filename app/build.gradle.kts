plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "team.replika"
    compileSdk = 35
    defaultConfig { applicationId = "team.replika"; minSdk = 26; targetSdk = 35; versionCode = 4; versionName = "0.4.0" }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // sherpa JNI resolves the boxed invoke(IIJ) method of Kotlin lambda classes.
    kotlinOptions { jvmTarget = "17"; freeCompilerArgs += "-Xlambdas=class" }
    ndkVersion = "27.1.12297006"
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    packaging { jniLibs { pickFirsts += "**/libc++_shared.so" } }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/modelAssets"))
    androidResources { noCompress += listOf("onnx", "gguf") }
}
val modelNames = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "segmentation.onnx", "embedding.onnx", "llm.gguf", "manifest.json")
val bundleModels by tasks.registering(Sync::class) {
    from(rootProject.file(".models")) { include(modelNames) }
    into(layout.buildDirectory.dir("generated/modelAssets/models"))
    doFirst {
        modelNames.forEach { name ->
            check(rootProject.file(".models/$name").let { it.isFile && it.length() > 0 }) {
                "Missing bundled model $name. Run scripts/setup-engine.ps1 before building."
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(bundleModels) }
dependencies {
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
