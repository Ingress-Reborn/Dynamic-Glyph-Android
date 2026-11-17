plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.szres.glyphlens"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "org.szres.glyphlens"
        minSdk = 29
        targetSdk = 37
        versionCode = 10
        versionName = "0.4.6"
        testInstrumentationRunner = providers.gradleProperty("glyphTestRunner")
            .orElse("androidx.test.runner.AndroidJUnitRunner").get()
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core)
    implementation(project(":recognition"))

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
