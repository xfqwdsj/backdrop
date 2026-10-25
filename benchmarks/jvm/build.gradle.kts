import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.benchmark)
    alias(libs.plugins.spotless)
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }

    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    sourceSets {
        jvmMain {
            dependencies {
                implementation(project(":backdrop"))
                implementation(project(":benchmarks:scenarios"))
                implementation(libs.kotlinx.benchmark.runtime)
                implementation(libs.skiko.runtime)
            }
        }
    }
}

val smoke = providers.gradleProperty("benchmarkSmoke").map(String::toBoolean).getOrElse(false)
val warmupCount = if (smoke) 1 else 5
val measurementCount = if (smoke) 2 else 8
val iterationMillis = if (smoke) 100L else 1000L
val forkCount = if (smoke) 1 else 2

benchmark {
    targets {
        register("jvm")
    }
    configurations {
        named("main") {
            warmups = warmupCount
            iterations = measurementCount
            iterationTime = iterationMillis
            iterationTimeUnit = "ms"
            mode = "avgt"
            outputTimeUnit = "us"
            reportFormat = "json"
            advanced("jvmForks", forkCount)
        }
    }
}

val benchmarkInclude = providers.gradleProperty("benchmarkInclude").getOrElse(".*")

// Invoke JMH directly so profiler failures fail the task and allocation metrics reach the report.
tasks.register<JavaExec>("jvmAllocationBenchmark") {
    group = "benchmark"
    description = "Measures warmed JVM graph time and allocation with JMH's GC profiler."
    dependsOn("jvmBenchmarkCompile")
    val runner = tasks.named<JavaExec>("jvmBenchmark")
    classpath = files(provider { runner.get().classpath })
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        },
    )
    mainClass.set("org.openjdk.jmh.Main")
    val report =
        layout.buildDirectory.file(
            "reports/benchmarks/${if (smoke) "allocation-smoke" else "allocation"}/jvmBenchmark.json",
        )
    doFirst {
        report.get().asFile.parentFile.mkdirs()
    }
    args(
        benchmarkInclude,
        "-wi",
        warmupCount.toString(),
        "-i",
        measurementCount.toString(),
        "-w",
        "${iterationMillis}ms",
        "-r",
        "${iterationMillis}ms",
        "-f",
        forkCount.toString(),
        "-jvmArgsAppend",
        "-Xms512m -Xmx512m -Djava.awt.headless=true",
        "-bm",
        "avgt",
        "-tu",
        "us",
        "-prof",
        "gc",
        "-foe",
        "true",
        "-rf",
        "json",
        "-rff",
        report.get().asFile.absolutePath,
    )
}

tasks.register<JavaExec>("listWorkloads") {
    group = "benchmark"
    description = "Lists JMH-discovered benchmark methods and parameter domains."
    dependsOn("jvmBenchmarkCompile")
    val runner = tasks.named<JavaExec>("jvmBenchmark")
    classpath = files(provider { runner.get().classpath })
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    mainClass.set("org.openjdk.jmh.Main")
    args("-lp", benchmarkInclude)
}

tasks.withType<JavaExec>().configureEach {
    if (name.startsWith("jvmBenchmark")) {
        javaLauncher.set(
            javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(25))
            },
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    if (name == "jvmBenchmarkCompile") {
        javaCompiler.set(
            javaToolchains.compilerFor {
                languageVersion.set(JavaLanguageVersion.of(25))
            },
        )
        options.release.set(11)
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
