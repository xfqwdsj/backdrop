import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.spotless)
}

android {
    namespace = "top.ltfan.backdrop.benchmark"
    compileSdk {
        version =
            release(37) {
                minorApiLevel = 2
            }
    }
    defaultConfig {
        applicationId = "top.ltfan.backdrop.benchmark"
        minSdk = 29
        targetSdk = 37
    }
    buildFeatures {
        compose = true
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":benchmarks:scenarios"))
    implementation(libs.androidx.activity.compose)
}

spotless {
    kotlin {
        target("src/*/kotlin/**/*.kt")
        ktfmt().kotlinlangStyle()
    }

    kotlinGradle {
        ktfmt().kotlinlangStyle()
    }
}
