plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}

/**
 * The same stamp the APK is built with, printed for local inspection. Read it
 * at execution time: a packaging invocation has already bumped the counter
 * during configuration, so this prints exactly what that build stamps.
 */
tasks.register("printVersionName") {
    doLast {
        val stamp = com.geno1024.netdis.gradle.Versioning.stamp(rootDir, file("app"))
        println(stamp.versionName)
    }
}
