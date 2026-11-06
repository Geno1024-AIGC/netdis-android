package com.geno1024.netdis.gradle

import org.gradle.api.Project
import java.io.File

/**
 * A build stamp of the form `0.1.<env>.<pack>.<sha1>`, matching the sibling
 * Android projects:
 *
 * - `env` orders published builds. On CI it is the GitHub run number, locally
 *   it falls back to the commit count.
 * - `pack` counts packaging events in this checkout, so two local builds of the
 *   same commit remain distinguishable.
 * - `sha1` ties the APK back to its source revision.
 */
data class Stamp(
    val env: String,
    val pack: String,
    val sha1: String,
) {
    val versionName: String get() = "$BASE_VERSION.$env.$pack.$sha1"

    /** Android `versionCode`; strictly monotonic within the published stream. */
    val versionCode: Int get() = (env.toIntOrNull() ?: 0).coerceAtLeast(1)

    companion object {
        const val BASE_VERSION = "0.1"
    }
}

object Versioning {
    private val NO_HEAD = "00000000"

    private val ANDROID_PACKAGING = setOf(
        "assembleDebug", "assembleRelease", "bundleDebug", "bundleRelease",
    )

    /**
     * Aggregates that reach a packaging task transitively, so that a plain
     * `build` still counts as a packaging event for the Android module.
     */
    private val AGGREGATE = setOf("build", "assemble")

    fun stamp(root: File, moduleDir: File): Stamp = Stamp(
        env = env(root),
        pack = packOf(moduleDir),
        sha1 = sha1(root),
    )

    fun env(root: File): String =
        System.getenv("GITHUB_RUN_NUMBER")
            ?: git(root, "rev-list", "--count", "HEAD")
            ?: "0"

    fun sha1(root: File): String =
        git(root, "rev-parse", "--short=8", "HEAD") ?: NO_HEAD

    fun packFile(moduleDir: File) = File(moduleDir, "count.pack")

    private fun packOf(moduleDir: File): String =
        packFile(moduleDir).readText().trim().ifEmpty { "0" }

    /**
     * Increments `<module>/count.pack` when this session requests packaging of
     * the module.
     *
     * The bump is a side effect of a packaging event, so the value stamped into
     * the current build is the count of *previously* completed events. CI reads
     * the stamp back out of the built APK rather than recomputing it, which
     * keeps the published version and the artifact in agreement.
     */
    fun bumpOnPackaging(project: Project, moduleDir: File, android: Boolean) {
        val names = if (android) ANDROID_PACKAGING else ANDROID_PACKAGING
        val requested = project.gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
        if (requested.any { names.contains(it) || AGGREGATE.contains(it) }) {
            incrementPack(moduleDir)
        }
    }

    private fun incrementPack(moduleDir: File) {
        val file = packFile(moduleDir)
        val current = file.readText().trim().toIntOrNull() ?: 0
        file.writeText("${current + 1}\n")
    }

    private fun git(root: File, vararg args: String): String? = try {
        val process = ProcessBuilder(listOf("git", "-C", root.absolutePath) + args)
            .redirectErrorStream(false)
            .start()
        val out = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && out.isNotEmpty()) out else null
    } catch (_: Exception) {
        null
    }
}
