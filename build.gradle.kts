plugins {
    java
    `java-library`
}

val major = "0.1"
val build = System.getenv("BUILD_NUMBER") ?: "dev"
version = "$major.$build"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withJavadocJar()
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    // No NMS imports; everything else via reflection
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to (project.version as String))
    }
}
