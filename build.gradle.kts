plugins {
    val kotlinVersion = "1.3.72"
    
    kotlin("jvm") version "1.3.72"
    kotlin("plugin.serialization") version kotlinVersion
}

group = "com.johnturkson.demo"
version = "0.0.1"

repositories {
    mavenCentral()
    jcenter()
}

dependencies {
    val serializationVersion = "0.20.0"
    val ktorVersion = "1.3.2"
    val postgresqlVersion = "42.2.12"
    val sqliteVersion = "3.30.1"
    
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime:${serializationVersion}")
    implementation( "io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-websockets:$ktorVersion")
    implementation("io.ktor:ktor-serialization:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    implementation("io.ktor:ktor-client-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-serialization-jvm:$ktorVersion")
    implementation("org.postgresql:postgresql:$postgresqlVersion")
}

tasks {
    wrapper {
        gradleVersion = "6.3"
        distributionType = Wrapper.DistributionType.ALL
    }
    
    compileKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }
    
    compileTestKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }
}
