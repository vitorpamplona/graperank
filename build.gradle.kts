plugins {
    kotlin("jvm") version "2.2.20"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.vitorpamplona.graperank"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    google()
}

val neo4jVersion = "5.26.0"

dependencies {
    implementation("com.vitorpamplona.quartz:quartz:1.05.0-SNAPSHOT")

    // Neo4j procedure API (provided by Neo4j at runtime)
    compileOnly("org.neo4j:neo4j:$neo4jVersion")

    testImplementation(kotlin("test"))
    testImplementation("org.neo4j.test:neo4j-harness:$neo4jVersion")
    testImplementation("org.neo4j.driver:neo4j-java-driver:5.27.0")
}

// Build a fat JAR for Neo4j plugin deployment (excludes Neo4j itself)
tasks.shadowJar {
    archiveClassifier.set("neo4j-plugin")
    dependencies {
        exclude(dependency("org.neo4j:.*:.*"))
        exclude(dependency("org.neo4j.test:.*:.*"))
        exclude(dependency("org.neo4j.driver:.*:.*"))
    }
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