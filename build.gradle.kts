plugins {
    kotlin("multiplatform") version "2.2.20"
    id("com.android.library") version "8.7.3"
}

group = "com.vitorpamplona.graperank"
version = "1.0-SNAPSHOT"

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                }
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
    jvmToolchain(21)

    sourceSets {
        androidMain.dependencies {
            implementation("com.vitorpamplona.quartz:quartz:1.05.0-SNAPSHOT")
        }
        getByName("androidUnitTest").dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.vitorpamplona.graperank"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.all {
            it.minHeapSize = "512m"
            it.maxHeapSize = "5G"
        }
    }
}
