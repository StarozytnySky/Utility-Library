import org.apache.tools.ant.taskdefs.Java

plugins {
    `java-library`
    java
    `maven-publish`
    `kotlin-dsl`
    signing
    alias(libs.plugins.shadow)
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

group = "org.broken.arrow.library"
version = "1.0-SNAPSHOT"
apply(plugin = "java")
apply(plugin = "maven-publish")

repositories {
    mavenCentral()
    mavenLocal()
    gradlePluginPortal()
}

subprojects {
    apply(plugin = "maven-publish")
    apply(plugin = "java")
    apply(plugin = "signing")
    tasks {
        javadoc {
            options.encoding = Charsets.UTF_8.name()
        }
        compileJava {
            options.encoding = Charsets.UTF_8.name()

            // Set the release flag. This configures what version bytecode the compiler will emit, as well as what JDK APIs are usable.
            // See https://openjdk.java.net/jeps/247 for more information.
            //options.release.set(8)
            java.sourceCompatibility = JavaVersion.VERSION_1_8
            java.targetCompatibility = JavaVersion.VERSION_1_8
        }

            val sourceSets = project.extensions.getByName("sourceSets") as SourceSetContainer
            register<Jar>("sourcesJar") {
                archiveClassifier.set("sources")
                from(sourceSets["main"].allSource)
            }
    }

    publishing {
        publications {
            create<MavenPublication>("mavenJava") {

                artifact(project.tasks.named<Jar>("sourcesJar").get()) {
                    classifier = "sources"
                }
                from(components["java"])
                artifactId = project.name
                groupId = project.group.toString()
                artifactId = project.name
                version = "0.107"
                pom {
                    name.set(project.name)
                    description.set("Description for ${project.name}")
                    url.set("https://github.com/broken1arrow/Utility-Library")

                    developers {
                        developer {
                            id.set("yourId")
                            name.set("broken-arrow")
                            email.set("not set")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/broken1arrow/Utility-Library")
                        developerConnection.set("scm:git:ssh://github.com/broken1arrow/Utility-Library")
                        url.set("https://github.com/broken1arrow/Utility-Library")
                    }
                }
            }
        }

        repositories {
            maven {
                name = "OSSRH"
                url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                credentials {
                    username = findProperty("ossrhUsername") as String? ?: ""
                    password = findProperty("ossrhPassword") as String? ?: ""
                }
            }
        }
    }

   signing {
      /* val signingKey = findProperty("signing.key") as String?
       signingKey?.let { file(it).readText(Charsets.UTF_8) }*/
        useInMemoryPgpKeys(
           findProperty("signing.keyId") as String?,
            findProperty("signing.key") as String?,
            findProperty("signing.password") as String?
        )
        sign(publishing.publications["mavenJava"])
    }

}

nexusPublishing {
    repositories {
        sonatype {
            username.set(findProperty("ossrhUsername") as String?)
            password.set(findProperty("ossrhPassword") as String?)
        }
    }
}

fun setProjectVersion(project: Project) {
    if (project.version == "" || project.version == "1.0-SNAPSHOT") project.version = version
}

tasks {
    javadoc {
        options.encoding = Charsets.UTF_8.name() // We want UTF-8 for everything
    }
    compileJava {
        options.encoding = Charsets.UTF_8.name()

        // Set the release flag. This configures what version bytecode the compiler will emit, as well as what JDK APIs are usable.
        // See https://openjdk.java.net/jeps/247 for more information.
        options.release.set(8)

        java.sourceCompatibility = JavaVersion.VERSION_1_8
        java.targetCompatibility = JavaVersion.VERSION_1_8
    }
}

