import java.util.Base64
import java.util.Properties
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing has exactly two channels, resolved in this order:
 *
 *  1. Local:  ./keystore.properties  (git-ignored, developer machine only)
 *  2. CI:     environment variables   (TODO_KEYSTORE_BASE64 | TODO_KEYSTORE_PATH,
 *                                      TODO_KEYSTORE_PASSWORD, TODO_KEY_ALIAS, TODO_KEY_PASSWORD)
 *
 * Both channels feed the same [ReleaseSigning] value object, so there is exactly one
 * implementation of "how a release gets signed". If neither channel is configured the
 * release build stays unsigned instead of silently falling back to the debug key.
 */
data class ReleaseSigning(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

fun resolveReleaseSigning(): ReleaseSigning? {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        val props = Properties()
        propsFile.inputStream().use { props.load(it) }
        val path = props.getProperty("storeFile")?.takeIf { it.isNotBlank() } ?: return null
        val file = rootProject.file(path)
        if (!file.exists()) return null
        return ReleaseSigning(
            storeFile = file,
            storePassword = props.getProperty("storePassword").orEmpty(),
            keyAlias = props.getProperty("keyAlias").orEmpty(),
            keyPassword = props.getProperty("keyPassword").orEmpty(),
        )
    }

    val base64 = env("TODO_KEYSTORE_BASE64")
    val path = env("TODO_KEYSTORE_PATH")
    val storeFile = when {
        base64 != null -> layout.buildDirectory.file("ci-keystore.jks").get().asFile.also { target ->
            target.parentFile.mkdirs()
            target.writeBytes(Base64.getDecoder().decode(base64))
        }
        path != null -> File(path)
        else -> return null
    }
    if (!storeFile.exists()) return null
    val storePassword = env("TODO_KEYSTORE_PASSWORD") ?: return null
    val keyAlias = env("TODO_KEY_ALIAS") ?: return null
    return ReleaseSigning(
        storeFile = storeFile,
        storePassword = storePassword,
        keyAlias = keyAlias,
        keyPassword = env("TODO_KEY_PASSWORD") ?: storePassword,
    )
}

val releaseSigning = resolveReleaseSigning()

// Tag-driven releases override these from CI (-PversionName=v1.2.3 → 1.2.3).
val versionNameOverride: String? = providers.gradleProperty("versionName").orNull
val versionCodeOverride: Int? = providers.gradleProperty("versionCode").orNull?.toIntOrNull()

// Single source of truth for the shipped version name; also used to name the APK.
val defaultVersionName: String = versionNameOverride ?: "1.0.2"

/** 1.2.3 -> 10203, the same scheme the release workflow derives from a tag. */
fun semverToVersionCode(version: String): Int {
    val parts = version.split(".")
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return major * 10_000 + minor * 100 + patch
}

android {
    namespace = "app.todo"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.todo"
        minSdk = 30
        targetSdk = 36
        versionCode = versionCodeOverride ?: semverToVersionCode(defaultVersionName)
        versionName = defaultVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = releaseSigning.storeFile
                storePassword = releaseSigning.storePassword
                keyAlias = releaseSigning.keyAlias
                keyPassword = releaseSigning.keyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    androidResources {
        localeFilters += listOf("en", "zh")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
        unitTests.all { it.useJUnit() }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json",
        )
    }
}

/**
 * Renames the assembled release artifact to `Todo-<version>.apk`.
 *
 * AGP 8.13 exposes no API to set the output file name any more: the new
 * `androidComponents` API has no `VariantOutput.outputFileName`, and the legacy
 * `com.android.build.VariantOutput` only offers `getOutputFile()`. So we build
 * the APK and rename it ourselves. CI calls this task instead of `assembleRelease`.
 */
val renameReleaseApk = tasks.register("renameReleaseApk") {
    group = "build"
    description = "Rename the release APK to Todo-<version>.apk"
    dependsOn("assembleRelease")
    doLast {
        val dir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val source = File(dir, "app-release.apk")
        val target = File(dir, "Todo-$defaultVersionName.apk")
        if (!source.exists()) {
            logger.lifecycle("Release APK already renamed: ${target.name}")
            return@doLast
        }
        if (!source.renameTo(target)) {
            throw GradleException("Failed to rename $source to $target")
        }
        logger.lifecycle("Release APK renamed to ${target.name}")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

tasks.withType<Test>().configureEach {
    // StringResourceConsistencyTest reads res/ and src/ from disk, which Gradle cannot
    // infer, so declare them: editing a string must re-run the check instead of
    // reporting a stale UP-TO-DATE result.
    inputs.dir(layout.projectDirectory.dir("src/main/res")).withPropertyName("consistencyRes").optional()
    inputs.dir(layout.projectDirectory.dir("src/main/java")).withPropertyName("consistencySources").optional()
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
