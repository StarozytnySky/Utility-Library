import org.broken.arrow.library.ShadeLogic

plugins {
    alias(libs.plugins.shadow)

    id("java")
    id("java-library")
    id("org.broken.arrow.library.LoadDependency")
}

group = "org.broken.arrow.library"
description = "Utility-Library"
version = "2.2.0-SNAPSHOT"

dependencies {
    api(project(":color-conversion"))
    api(project(":menu-library"))
    api(project(":item-creator"))
    api(project(":database"))
    api(project(":commands"))
    api(project(":yaml-utility"))
    api(project(":block-visualization"))
    api(project(":title-update"))
    api(project(":conversation-prompt"))
    api(project(":localization"))
    api(project(":menu-configuration-manager"))
    api(project(":serialize-utility"))
    api(project(":nbt"))
    api(project(":log-and-validate"))
    api(project(":chunk-relevance"))

    compileOnly(libs.org.spigotmc.spigotapi)
    compileOnly(libs.google.findbugs.jsr305)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

/* work in progress
val sourcesJar = tasks.findByName("sourcesJar") ?: tasks.register("sourcesJar", Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

afterEvaluate {

    tasks.named("publishMavenJavaPublicationToMavenLocal") {
        dependsOn(tasks.named("jar"))
    }
*/
/*    publishing {
        publications {
            named<MavenPublication>("mavenJava") {
                // Attach sources and javadoc explicitly
                from(components["java"])
            }
        }
    }*//*

}
*/


tasks {
    shadowJar {
        mergeServiceFiles()
        mustRunAfter(":block-visualization:shadowJar")
        mustRunAfter(":nbt:shadowJar")
        mustRunAfter(":color-conversion:shadowJar")
        mustRunAfter(":item-creator:shadowJar")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        ShadeLogic(project, this) {
            archiveClassifier.set("")
            setArchiveFileName()
            dependencies {
                exclusions.forEach { exclude(it) }
                //exclude("de/tr7zw/changeme/nbtapi/")
            }
            relocate("de.tr7zw.changeme.nbtapi", formatDependency("nbt_util"))
            //relocate("org.broken.arrow.library", formatDependency("api"))
        }
        // Drivers are supplied once by Paper using plugin.yml libraries.
        // Prevent duplicate copies from being included by Shadow.
        dependencies {
            exclude(dependency("com.zaxxer:HikariCP:.*"))
            exclude(dependency("org.mongodb:.*:.*"))
            exclude(dependency("org.xerial:sqlite-jdbc:.*"))
            exclude(dependency("com.h2database:h2:.*"))
            exclude(dependency("com.mysql:mysql-connector-j:.*"))
            exclude(dependency("org.mariadb.jdbc:mariadb-java-client:.*"))
            exclude(dependency("org.postgresql:postgresql:.*"))
            exclude(dependency("com.google.code.gson:gson:.*"))
            exclude(dependency("org.slf4j:slf4j-api:.*"))
        }
    }
    processResources {
        filteringCharset = Charsets.UTF_8.name() // We want UTF-8 for everything
        filesMatching("plugin.yml") {
            expand(
                mapOf(
                    "version" to project.version,
                    "main" to "org.broken.arrow.utility.library.UtilityLibrary",//"${project.group}.${project.name}",
                    "name" to project.name
                )
            )
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