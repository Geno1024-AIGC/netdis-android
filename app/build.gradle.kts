import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val buildRun: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
    ?: (findProperty("canaryRunNumber") as String?)?.toIntOrNull()
    ?: 1
val buildCommit: String = System.getenv("GITHUB_SHA") ?: "local"
val buildVersionName = "0.1.$buildRun.${buildCommit.take(8)}"

android {
    namespace = "com.geno1024.netdis"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.geno1024.netdis"
        minSdk = 26
        targetSdk = 37
        versionCode = buildRun
        versionName = buildVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The org's shared signing key, read exactly the way RULES/KEYSTORE.md orders
    // it: environment first, then the git-ignored local.properties. A missing key
    // only means the resulting APK cannot replace an installed one.
    val signingProps = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    fun signingSecret(property: String, env: String): String? =
        System.getenv(env)?.takeIf { it.isNotBlank() }
            ?: signingProps.getProperty(property)?.takeIf { it.isNotBlank() }

    val signingStoreFile = signingSecret("keystore.storeFile", "KEYSTORE_STORE_FILE")
    val signingStorePassword = signingSecret("keystore.storePassword", "KEYSTORE_STORE_PASSWORD")

    signingConfigs {
        if (signingStoreFile != null && signingStorePassword != null) {
            create("release") {
                storeFile = rootProject.file(signingStoreFile)
                storePassword = signingStorePassword
                keyPassword = signingSecret("keystore.keyPassword", "KEYSTORE_KEY_PASSWORD")
                    ?: signingStorePassword
                keyAlias = signingSecret("keystore.keyAlias", "KEYSTORE_KEY_ALIAS") ?: "geno"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
