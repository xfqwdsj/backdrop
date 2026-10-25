import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.spotless)
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    explicitApi()

    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    android {
        namespace = "top.ltfan.backdrop.benchmark.scenarios"
        compileSdk {
            version =
                release(37) {
                    minorApiLevel = 2
                }
        }
        minSdk = 29
    }
    sourceSets {
        commonMain {
            dependencies {
                api(project(":backdrop"))
                api(libs.compose.foundation)
                api(libs.compose.animation)
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.skiko.runtime)
            }
        }
    }
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
