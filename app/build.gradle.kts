import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "xyz.mpv.rex.addon.ytdl"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    flavorDimensions += "abi"

    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        create("arm32") {
            dimension = "abi"
            ndk {
                abiFilters += "armeabi-v7a"
            }
        }
        create("x86") {
            dimension = "abi"
            ndk {
                abiFilters += "x86"
            }
        }
        create("x86_64") {
            dimension = "abi"
            ndk {
                abiFilters += "x86_64"
            }
        }
        create("universal") {
            dimension = "abi"
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }
        }
    }

    defaultConfig {
        applicationId = "xyz.mpv.rex.addon.ytdl"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            val keyStorePath = (findProperty("RELEASE_STORE_FILE") as? String)
                ?: System.getenv("RELEASE_STORE_FILE")
            if (keyStorePath != null && file(keyStorePath).exists()) {
                storeFile = file(keyStorePath)
                storePassword = (findProperty("RELEASE_STORE_PASSWORD") as? String)
                    ?: System.getenv("RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = (findProperty("RELEASE_KEY_ALIAS") as? String)
                    ?: System.getenv("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = (findProperty("RELEASE_KEY_PASSWORD") as? String)
                    ?: System.getenv("RELEASE_KEY_PASSWORD") ?: ""
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
