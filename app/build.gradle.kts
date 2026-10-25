plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStore = rootProject.file("keystore.p12")
val releasePassword = providers.environmentVariable("KEYSTORE_PASS").orNull
val canSignRelease = releaseStore.isFile && !releasePassword.isNullOrBlank()

android {
    namespace = "io.github.arturaragao.limpafotos"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.arturaragao.limpafotos"
        minSdk = 30
        targetSdk = 35
        versionCode = providers.gradleProperty("versionCode").orElse("3").get().toInt()
        versionName = providers.gradleProperty("versionName").orElse("0.1.3").get()
        // Só processadores de celular: as libs x86 do ML Kit somavam 44 MB e faziam o download da atualização cair.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseStore
                storeType = "PKCS12"
                storePassword = releasePassword
                keyAlias = "fotos"
                keyPassword = releasePassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSignRelease) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
}
