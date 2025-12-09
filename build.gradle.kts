plugins {
    kotlin("jvm") version "2.2.20"
}

group = "com.vitorpamplona.graperank"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    google()
}

dependencies {
    implementation("com.vitorpamplona.quartz:quartz:1.05.0-SNAPSHOT")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
    jvmToolchain(21)
}

tasks.withType<Test> {
    // Set the minimum heap size for the test JVM
    minHeapSize = "512m"
    // Set the maximum heap size for the test JVM
    maxHeapSize = "5G"

    // Add other JVM arguments if needed (e.g., for Metaspace in modern Java versions)
    // jvmArgs = listOf("-XX:MaxMetaspaceSize=512m")
}