plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.pixelwarp"
version = "1.0.1"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile> {
    options.release.set(21)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.google.code.gson:gson:2.11.0")
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        relocate("com.zaxxer.hikari", "com.pixelwarp.lib.hikari")
    }

    build {
        dependsOn(shadowJar)
    }

    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
}
