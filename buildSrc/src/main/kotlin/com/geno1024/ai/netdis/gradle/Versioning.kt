package com.geno1024.ai.netdis.gradle

import org.gradle.api.Project
import java.io.File
import java.io.RandomAccessFile

/**
 * A build stamp of the form `0.1.<env>.<pack>.<sha1>`, matching the sibling
 * Android projects:
 *
 * - `env` orders published builds. On CI it is the GitHub run number, locally
 *   it falls back to the commit count.
 * - `pack` counts packaging builds of this module: attempts, not successes, so a
 *   build that fails after configuration still has its number. CI reuses the
 *   committed number instead of incrementing.
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

    /**
     * `build` is an aggregate every module has, but as a prefix it would also
     * catch `buildEnvironment`.
     */
    private val PACKAGING_EXACT = setOf("build")

    /** Android: every variant reaches the APK/AAB through one of these. */
    private val ANDROID_PACKAGING_PREFIXES = listOf("assemble", "bundle", "package", "install")

    /** JVM: exact names only, so that a bare `assembleDebug` does not count. */
    private val JVM_PACKAGING_EXACT = setOf("assemble", "jar", "installDist", "distTar", "distZip")

    /** JVM: names that only ever appear with a suffix. */
    private val JVM_PACKAGING_PREFIXES = listOf("shadowJar", "dist", "publish")

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

    private fun packOf(moduleDir: File): String {
        val file = packFile(moduleDir)
        return if (file.exists()) file.readText().trim().ifEmpty { "0" } else "0"
    }

    /**
     * Counts this session's packaging build of the module.
     *
     * Call it before [stamp], so the artifact carries its own one-based number and
     * the file equals the number of packaging builds that have happened.
     *
     * The count is taken at configuration time, which is why it records *requests*
     * and not successes: a build that fails to compile, cannot sign or is
     * interrupted after configuration already has its number. Query-only tasks
     * (`tasks`, `help`, …) and `--dry-run` stay out. `-Ppack.bump=true|false`
     * forces the counter on or off; CI skips it by default, because an increment
     * that is never written back would invent a number nobody has seen.
     */
    fun bumpOnPackaging(project: Project, moduleDir: File) {
        val params = project.gradle.startParameter
        if (params.isDryRun) return
        val forced = params.projectProperties["pack.bump"]?.toBoolean()
        if (forced == false) return
        if (forced == null && !System.getenv("CI").isNullOrEmpty()) return
        if (params.taskNames.none { requestsPackaging(project, it) }) return
        incrementPack(moduleDir)
    }

    /**
     * Whether [taskName], as requested on the command line, packages anything
     * here.
     *
     * A qualified name (`:git-core:jar`) belongs to one module only, and every
     * module sees the same `taskNames`, so without the path check a JVM module's
     * jar would also bump the app's counter. A bare name (`assembleDebug`) is how
     * Gradle asks every module that has the task, so it counts in each of them —
     * provided the module actually has such a task, see [packagesAnything].
     */
    private fun requestsPackaging(project: Project, taskName: String): Boolean {
        val name = taskName.substringAfterLast(':')
        if (!packagesAnything(project, name)) return false
        if (!taskName.contains(':')) return true
        val path = taskName.removePrefix(":")
        val owner = if (path.contains(':')) ":" + path.substringBeforeLast(':') else ":"
        return owner == project.path
    }

    /**
     * Android and JVM modules package through different task names. A bare
     * `assembleDebug` exists only for the former, so treating `assemble*` as a
     * prefix everywhere would charge JVM modules for builds they never run.
     */
    private fun packagesAnything(project: Project, name: String): Boolean {
        if (name in PACKAGING_EXACT) return true
        val android = project.plugins.hasPlugin("com.android.application") ||
            project.plugins.hasPlugin("com.android.library")
        return if (android) {
            ANDROID_PACKAGING_PREFIXES.any(name::startsWith)
        } else {
            name in JVM_PACKAGING_EXACT || JVM_PACKAGING_PREFIXES.any(name::startsWith)
        }
    }

    /**
     * Serialised through a file lock: two builds of the same checkout would read
     * the same value and the later write would swallow the earlier increment.
     */
    private fun incrementPack(moduleDir: File) {
        val file = packFile(moduleDir)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.channel.lock().use {
                val bytes = ByteArray(raf.length().toInt())
                raf.seek(0L)
                raf.readFully(bytes)
                val current = String(bytes, Charsets.UTF_8).trim().toIntOrNull() ?: 0
                raf.setLength(0L)
                raf.seek(0L)
                raf.write("${current + 1}\n".toByteArray())
            }
        }
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
