val kotlin_version: String by project
val ktor_version: String by project
val logback_version: String by project
val exposed_version: String by project
val postgres_version: String by project
val hikari_version: String by project
val lettuce_version: String by project
val libsignal_version: String by project

plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
}

group = "com.lowkey.backend"
version = "0.0.1"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

repositories {
    mavenCentral()
    maven {
        name = "SignalBuildArtifacts"
        url = uri("https://build-artifacts.signal.org/libraries/maven/")
    }
}

dependencies {
    implementation(platform("io.ktor:ktor-bom:$ktor_version"))
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-websockets")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-auth")

    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")
    implementation("org.postgresql:postgresql:$postgres_version")
    implementation("com.zaxxer:HikariCP:$hikari_version")

    implementation("io.lettuce:lettuce-core:$lettuce_version")

    // Official Signal Protocol library — used only to verify signed-challenge
    // auth signatures. No message decryption anywhere on the server.
    implementation("org.signal:libsignal-client:$libsignal_version")

    // FCM HTTP v1 — Google OAuth for service accounts + Ktor client
    implementation("com.google.auth:google-auth-library-oauth2-http:1.30.0")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-client-logging")

    implementation("ch.qos.logback:logback-classic:$logback_version")

    testImplementation(platform("io.ktor:ktor-bom:$ktor_version"))
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "io.ktor.server.netty.EngineMain"
    }
}
