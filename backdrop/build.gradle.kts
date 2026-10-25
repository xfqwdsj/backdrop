import com.android.build.api.dsl.ManagedVirtualDevice
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.spotless)
    alias(libs.plugins.dokka)
    alias(libs.plugins.mavenPublish)
    alias(libs.plugins.ksp)
    signing
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
        namespace = "top.ltfan.backdrop"
        compileSdk {
            version =
                release(37) {
                    minorApiLevel = 2
                }
        }
        androidResources.enable = true
        minSdk = 21
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }
            .configure {
                instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                animationsDisabled = true
                // AGP creates and tears down this device; CI only invokes the generated task.
                managedDevices {
                    localDevices {
                        create("pixel6Api37") {
                            device = "Pixel 6"
                            apiLevel = 37
                            systemImageSource = "google_apis"
                            pageAlignment = ManagedVirtualDevice.PageAlignment.FORCE_4KB_PAGES
                        }
                    }
                }
            }

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    macosArm64()
    iosSimulatorArm64()
    iosArm64()
    js {
        browser()
        binaries.executable()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
            dependencies {
                api(libs.compose.foundation)
                api(libs.compose.ui)
                api(libs.compose.ui.graphics)
                implementation(libs.annotations)
                api(libs.dslUtilities)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val renderTest =
            create("renderTest") {
                dependsOn(commonTest.get())
                dependencies {
                    implementation(libs.compose.ui.test)
                }
            }
        jvmTest {
            dependsOn(renderTest)
        }
        getByName("androidDeviceTest") {
            dependsOn(renderTest)
            dependencies {
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.junit)
                implementation(libs.androidx.activity.compose)
            }
        }

        val skikoMain =
            create("skikoMain") {
                dependsOn(commonMain.get())
            }

        jvmMain {
            dependsOn(skikoMain)
        }

        appleMain {
            dependsOn(skikoMain)
        }

        webMain {
            dependsOn(skikoMain)
        }
    }
}

dependencies {
    add("kspCommonMainMetadata", libs.dslUtilities.ksp)
}

tasks.configureEach {
    if (name.startsWith("ksp") && name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn("kspCommonMainKotlinMetadata")
}

tasks.configureEach {
    if (name.contains("sourcesJar", ignoreCase = true)) dependsOn("kspCommonMainKotlinMetadata")
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

dokka {
    dokkaSourceSets {
        configureEach {
            sourceLink {
                remoteUrl =
                    uri("https://github.com/xfqwdsj/backdrop/tree/v${version}/${project.name}")
            }
        }
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    pom {
        name = project.name
        description =
            "A Compose Multiplatform library for drawing backdrop effects behind UI content, with HDR support."
        url = "https://github.com/xfqwdsj/backdrop"

        licenses {
            license {
                name = "Apache License 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0"
                distribution = "repo"
            }
        }

        developers {
            developer {
                id = "Kyant0"
                name = "Kyant"
                email = "kyant2021@outlook.com"
                roles = listOf("Author")
            }

            developer {
                id = "xfqwdsj"
                name = "LTFan"
                email = "xfqwdsj@qq.com"
                roles = listOf("Maintainer")
            }
        }

        scm {
            connection = "scm:git:https://github.com/xfqwdsj/backdrop.git"
            developerConnection = "scm:git:https://github.com/xfqwdsj/backdrop.git"
            url = "https://github.com/xfqwdsj/backdrop"
        }
    }

    configure(
        KotlinMultiplatform(javadocJar = JavadocJar.Dokka(tasks.dokkaGeneratePublicationHtml)),
    )
}

publishing {
    repositories {
        maven {
            name = "gitHubPackages"
            url = uri("https://maven.pkg.github.com/xfqwdsj/backdrop")
            credentials(PasswordCredentials::class)
        }
    }
}

signing {
    sign(publishing.publications)
    val publishSigningMode = findProperty("publishSigningMode") as String?
    if (publishSigningMode == "inMemory") return@signing
    useGpgCmd()
}

group = "top.ltfan.backdrop"
