plugins {
    `java-library`
    alias(libs.plugins.shadow)
    id("org.broken.arrow.library.LoadDependency")
}

group = "org.broken.arrow.library"
description = "Database 2.5 — portable SQL/MongoDB database engine"
version = "2.5.0-SNAPSHOT"

// No legacy Java 8 compatibility for the rewritten Database module.
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    // Sources are registered by the root build for every library module.
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

// The Bukkit API used by the optional plugin bootstrap is a Spigot snapshot artifact.
repositories {
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

dependencies {
    // Keep the established Utility-Library module graph intact.
    api(project(":log-and-validate"))
    api(project(":serialize-utility"))
    api(project(":version"))
    // Bukkit configuration is used only by the optional Plugin bootstrap facade.
    compileOnly(libs.org.spigotmc.spigotapi)

    // Shared JSON library also used by Serialize Utility; required by JDBC structured fields.
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.mongodb:mongodb-driver-sync:5.14.0")

    // These drivers are bundled by consuming plugins as runtime dependencies.
    runtimeOnly("org.xerial:sqlite-jdbc:3.54.1.0")
    runtimeOnly("com.h2database:h2:2.5.252")
    runtimeOnly("com.mysql:mysql-connector-j:9.6.0")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:4.0.0")
    runtimeOnly("org.postgresql:postgresql:42.7.14")

    // Compile-time contracts supplied by the host library modules.
    compileOnly(libs.google.findbugs.jsr305)
    compileOnly(libs.org.apache.logging.log4j.log4j.api)
    compileOnly(libs.apache.logging.log4j.core)

    // DatabaseManager exposes Plugin overloads; testCompileJava must resolve that public type.
    testImplementation(libs.org.spigotmc.spigotapi)
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Fail early if a previously overlooked source tree is reintroduced by a merge.
val verifyNoLegacyDatabaseSources by tasks.registering {
    group = "verification"
    description = "Rejects legacy Database source packages after the breaking rewrite"
    doLast {
        val legacy = listOf("core", "connection", "construct", "builders", "utility", "modern")
            .map { file("src/main/java/org/broken/arrow/library/database/$it") }
            .filter { it.exists() }
        check(legacy.isEmpty()) {
            "Legacy Database packages are not allowed: ${legacy.joinToString()}"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyNoLegacyDatabaseSources)
}

// Keep the facade package intentionally small: feature types belong to their own package.
val verifyDatabasePackageLayout by tasks.registering {
    group = "verification"
    description = "Rejects accidental growth of the root Database API package"
    doLast {
        val apiDir = file("src/main/java/org/broken/arrow/library/database/api")
        val permitted = setOf("DatabaseClient.java", "DatabaseManager.java", "DatabaseFailure.java", "package-info.java")
        val unexpected = apiDir.listFiles()?.asSequence()
            ?.filter { it.isFile && it.extension == "java" && it.name !in permitted }
            ?.map { it.name }?.toList().orEmpty()
        check(unexpected.isEmpty()) { "Move feature types out of database.api: $unexpected" }
    }
}

tasks.named("check") {
    dependsOn(verifyDatabasePackageLayout)
}
