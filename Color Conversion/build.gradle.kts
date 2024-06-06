import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.broken.arrow.library.PublicationManager

plugins {
    java
    alias(libs.plugins.shadow)

    id ("java-library")
    id("org.broken.arrow.library.LoadDependency")
}

group = "org.broken.arrow.library.color"
description = "Color_Conversion"
version = "1.0-SNAPSHOT"

dependencies {
    api(project(":Log_and_Validate"))
    compileOnly(libs.com.google.code.gson.gson)
    compileOnly(libs.org.spigotmc.spigot.api)
    compileOnly(libs.com.google.code.findbugs.jsr305)
}

java {
    withJavadocJar()
}

tasks{
    PublicationManager(project) {
        val shadowJar by getting(ShadowJar::class) {
            archiveClassifier.set("all")
            mergeServiceFiles()
        }
        artifact(shadowJar) {
            classifier = "all"
        }
    }
}