plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}

/**
 * The same stamp the APK is built with, printed for local inspection. CI reads
 * the version back out of the built APK instead, because packaging bumps the
 * pack counter.
 */
tasks.register("printVersionName") {
    val stamp = com.geno1024.netdis.gradle.Versioning.stamp(rootDir, file("app"))
    doLast { println(stamp.versionName) }
}
