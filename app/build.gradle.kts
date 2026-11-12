plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val splitApks = providers.gradleProperty("evannaAbiSplits").map(String::toBoolean).getOrElse(false)

android {
    namespace = "com.evanna.player"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.evanna.player"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "com.evanna.player.playback.UsbAdapterProbe"
        if (!splitApks) ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }

    splits {
        abi {
            isEnable = splitApks
            reset()
            include(*supportedAbis.toTypedArray())
        }
    }

    buildFeatures { compose = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":usbaudio"))
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.session)
    testImplementation(libs.junit)
}
