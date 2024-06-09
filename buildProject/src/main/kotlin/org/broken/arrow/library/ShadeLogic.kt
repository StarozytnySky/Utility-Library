package org.broken.arrow.library

import org.gradle.api.Project
import org.gradle.api.tasks.bundling.AbstractArchiveTask



/**
 * Class to encapsulate logic for configuring the ShadowJar task.
 *
 * @param project The project to run this shade configuration.
 * @param task The current running task for which to set the file name.
 * @param shadeLogic create a anonymous part of this class you can use to sett property for the shadowJar.
 */
class ShadeLogic(private val project: Project, private val task: AbstractArchiveTask, shadeLogic: ShadeLogic.() -> Unit) {

    private var fileName : String = "${project.name}-${project.version}.jar"
    val exclusions : List<String> = setExclusions()
    init {
        shadeLogic(this)
    }


    /**
     * Formats the given package name as a dependency string. The resulting string will be in the following
     * format: "group.dependencies.packageName".
     *
     * @param packageName The package name for the dependency.
     * @return A formatted dependency string.
     */
    fun formatDependency(packageName: String): String {
        return "${project.group}.dependencies.$packageName"
    }


    /**
     * Sets the archive file name using the project's name and version.
     */
    fun setArchiveFileName() {
        task.archiveFileName.set(fileName)
    }

   private fun setExclusions() : List<String> = listOf(
        "*exclude.jar",
        "com/github/angeschossen/",
        "org/spigotmc/",
        "org/bukkit/",
        "org/yaml/snakeyaml/",
        "com/google/",
        "net/md_5/bungee/",
        "org/apache/commons/",
        "mojang-translations/",
        "javax/annotation/",
        "org/joml/",
        "org/checkerframework/",
        "META-INF/proguard/",
        "META-INF/versions/",
        "META-INF/maven/com.google.code.findbugs/",
        "META-INF/maven/com.google.code.gson/",
        "META-INF/maven/com.google.errorprone/",
        "META-INF/maven/com.google.guava/",
        "META-INF/maven/net.md-5/",
        "META-INF/maven/org.joml/",
        "META-INF/maven/org.spigotmc/",
        "META-INF/maven/org.yaml/"
    )
}
