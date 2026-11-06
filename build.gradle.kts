plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}

/**
 * The value that CI also publishes in `canary.properties` and that the in-app
 * updater compares against the installed versionCode.
 */
tasks.register("printVersionName") {
    val run = providers.environmentVariable("GITHUB_RUN_NUMBER").orElse("1").get()
    val sha = providers.environmentVariable("GITHUB_SHA").orElse("local").get().take(8)
    doLast { println("0.1.$run.$sha") }
}
