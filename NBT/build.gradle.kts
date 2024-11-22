import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.AppendingTransformer
import org.broken.arrow.library.PublicationManager
import org.broken.arrow.library.ShadeLogic

plugins {
    alias(libs.plugins.shadow)

    id ("java")
    id ("maven-publish")
    id("java-library")
    id("org.broken.arrow.library.LoadDependency")
}

group = "org.broken.arrow.library"
description = "NBT"
version = "1.0-SNAPSHOT"


dependencies {
    api(project(":log-and-validate"))
    api(libs.tr7zw.item.nbt.api)
    compileOnly(libs.org.spigotmc.spigotapi)
    compileOnly(libs.google.findbugs.jsr305)
}


java {
    withJavadocJar()
}

tasks {

    shadowJar {
        duplicatesStrategy = DuplicatesStrategy.INHERIT
        ShadeLogic(project, this) {
            setArchiveFileName()
            dependencies {
                exclusions.forEach { exclude(it) }
            }
            relocate("de.tr7zw.changeme.nbtapi", formatDependency("nbt"))
        }
    }

    /*    PublicationManager(project) {
            val shadowJar by getting(ShadowJar::class) {
                archiveClassifier.set("${description}_all")
                mergeServiceFiles()
            }
            artifact(shadowJar) {
                classifier = "all"
            }
        }*/
}

