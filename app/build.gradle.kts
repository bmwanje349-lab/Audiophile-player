import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val stableDebugKeystore = rootProject.file("ci/audiophile-debug.keystore")
if (!stableDebugKeystore.isFile) {
    val encoded = rootProject.file("ci/audiophile-debug.keystore.b64").readText().trim()
    stableDebugKeystore.parentFile.mkdirs()
    stableDebugKeystore.writeBytes(Base64.getDecoder().decode(encoded))
}

val productionStoreB64 =
    providers.environmentVariable("ANDROID_RELEASE_KEYSTORE_B64").orNull
val productionStorePassword =
    providers.environmentVariable("ANDROID_RELEASE_STORE_PASSWORD").orNull
val productionKeyAlias =
    providers.environmentVariable("ANDROID_RELEASE_KEY_ALIAS").orNull
val productionKeyPassword =
    providers.environmentVariable("ANDROID_RELEASE_KEY_PASSWORD").orNull

val productionSigningConfigured =
    !productionStoreB64.isNullOrBlank() &&
        !productionStorePassword.isNullOrBlank() &&
        !productionKeyAlias.isNullOrBlank() &&
        !productionKeyPassword.isNullOrBlank()

val productionKeystore =
    if (productionSigningConfigured) {
        rootProject.layout.buildDirectory
            .file("signing/production-release.keystore")
            .get()
            .asFile
            .also { file ->
                file.parentFile.mkdirs()
                if (!file.isFile) {
                    file.writeBytes(
                        Base64.getDecoder().decode(
                            productionStoreB64!!,
                        ),
                    )
                }
            }
    } else {
        null
    }

android {
    namespace = "com.example.musicplayer"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.example.musicplayer"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.4.1"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    signingConfigs {
        create("ciDebug") {
            storeFile = stableDebugKeystore
            storePassword = "audiophile-debug"
            keyAlias = "audiophile-debug"
            keyPassword = "audiophile-debug"
        }

        if (productionSigningConfigured) {
            create("production") {
                storeFile = productionKeystore
                storePassword = productionStorePassword
                keyAlias = productionKeyAlias
                keyPassword = productionKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("ciDebug")
        }

        release {
            // The real release variant is production-signed whenever the
            // repository supplies the four production signing environment
            // variables. CI uses the separate ciRelease variant when those
            // credentials are intentionally absent.
            if (productionSigningConfigured) {
                signingConfig = signingConfigs.getByName("production")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        create("ciRelease") {
            signingConfig = signingConfigs.getByName("ciDebug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    implementation("com.github.wendykierp:JTransforms:3.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
