import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Restores the old debug pipeline - R8, resource shrinking and all four ABIs - for the rare
// occasion something only misbehaves in an obfuscated build or on a non-arm64 emulator.
val minifyDebug = providers.gradleProperty("minifyDebug").map(String::toBoolean).orElse(false).get()

val noReleaseObfuscation = providers.gradleProperty("noReleaseObfuscation")
    .map(String::toBoolean)
    .orElse(false)
    .get()

// Encrypts string literals in the compiled bytecode (XOR + per-build random key), decrypted at
// runtime by the small `xor` library below - complements R8, which renames identifiers but
// leaves string constants (URLs, log messages, SSIDs, error text) sitting in the clear for
// anyone who unzips the APK and runs `strings` on classes.dex.
apply(plugin = "stringfog")

configure<com.github.megatronking.stringfog.plugin.StringFogExtension> {
    // Only ever assign `enable` to turn fogging OFF. Setting it to true here (which reads as a
    // no-op) left the transform inert: a clean build encrypted 0 of ~1200 app classes and R8
    // then stripped the unused xor library, shipping every string literal in the clear.
    if (noReleaseObfuscation) enable = false
    implementation = "com.github.megatronking.stringfog.xor.StringFogImpl"
    fogPackages = arrayOf("io.motohub.android")
}

val localSigningPropertiesFile = rootProject.projectDir.resolve(
    "../../tooling/private/android-auto/release-signing.properties"
)
val localSigningProperties = Properties().apply {
    if (localSigningPropertiesFile.isFile) {
        localSigningPropertiesFile.inputStream().use { stream -> load(stream) }
    }
}
val localSigningStore = localSigningProperties.getProperty("storeFile")?.let { configuredPath ->
    File(configuredPath).let { file ->
        if (file.isAbsolute) {
            file
        } else {
            rootProject.projectDir.resolve("../..").resolve(configuredPath).normalize()
        }
    }
}
val hasLocalReleaseSigning = localSigningStore?.isFile == true &&
    localSigningProperties.getProperty("storePassword") != null &&
    localSigningProperties.getProperty("keyAlias") != null &&
    localSigningProperties.getProperty("keyPassword") != null

val localSentryPropertiesFile = rootProject.projectDir.resolve("../../tooling/private/sentry.properties")
val localSentryProperties = Properties().apply {
    if (localSentryPropertiesFile.isFile) {
        localSentryPropertiesFile.inputStream().use { stream -> load(stream) }
    }
}

fun sentryDsn(propertyName: String, environmentName: String): String =
    providers.gradleProperty(propertyName).orNull
        ?: providers.environmentVariable(environmentName).orNull
        ?: localSentryProperties.getProperty(propertyName)
        ?: ""

fun asBuildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val coreSentryDsn = sentryDsn("sentryCoreDsn", "SENTRY_CORE_DSN")
// The diagnostics collector (n8n webhook). Same private file as the DSNs, so a source build
// without it simply never uploads - which is what every build from this repository does, because
// the value lives in sentry.properties / an environment variable and never in the tree.
//
// The key is a shared secret the webhook checks, so it is readable by anyone who unpacks a
// published APK. What it protects is the collector's tidiness, not a rider's data: every upload
// is consent-gated in the app, and abuse of the endpoint is a server-side question (rate
// limiting, rotation), not one a build flag can answer.
val diagnosticsEndpoint = sentryDsn("diagnosticsEndpoint", "MOTOHUB_DIAGNOSTICS_ENDPOINT")
val diagnosticsKey = sentryDsn("diagnosticsKey", "MOTOHUB_DIAGNOSTICS_KEY")
val sentryDebug = providers.gradleProperty("sentryDebug")
    .map(String::toBoolean)
    .orElse(false)
    .get()
android {
    namespace = "io.motohub.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.motohub.android"
        // 31 = Android 12: everything Android-12-specific is behind SDK_INT gates, so on 14+
        // the executed code paths are identical to the old minSdk-34 builds.
        minSdk = 31
        targetSdk = 36
        // CORE and ADVANCED ship as a pair, under one tag, and talk to each other over AIDL:
        // keep this identical to the PRO worktree's build.gradle.kts. They drifted after v1.1.4
        // (CORE reached 1.1.14/108 while ADVANCED sat at 1.1.6/100), which left a rider's
        // "MOTO-HUB 1.1.x" unable to identify which pair they actually had installed.
        versionCode = 206
        versionName = "1.1.112"
        buildConfigField("boolean", "IS_PRO", "false")
        buildConfigField("String", "SENTRY_DSN", asBuildConfigString(coreSentryDsn))
        buildConfigField("String", "DIAGNOSTICS_ENDPOINT", asBuildConfigString(diagnosticsEndpoint))
        buildConfigField("String", "DIAGNOSTICS_KEY", asBuildConfigString(diagnosticsKey))
        // -PsentryDebug=true makes the SDK narrate what it is doing to logcat. Telemetry that
        // silently does not arrive is worse than none, and there is no other way to see whether
        // a session was started, dropped or rejected: the SDK says nothing at all by default.
        buildConfigField("boolean", "SENTRY_DEBUG", sentryDebug.toString())
        manifestPlaceholders["appLabel"] = "MOTO-HUB"
    }

    signingConfigs {
        if (hasLocalReleaseSigning) {
            create("localRelease") {
                storeFile = localSigningStore
                storePassword = localSigningProperties.getProperty("storePassword")
                keyAlias = localSigningProperties.getProperty("keyAlias")
                keyPassword = localSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Local variant only — nothing built from "debug" is ever published: GitHub gets
            // release builds exclusively (exportPublicApk assembles the release variant).
            // Kept minified and non-debuggable so a local install behaves like the shipped
            // artifact; de-obfuscate stack traces with retrace + outputs/mapping/*/mapping.txt.
            // R8 and resource shrinking used to run here too, so that a debug install would
            // behave like the shipped artifact. Measured 2026-08-30, that fidelity cost 69s of
            // R8 and 39s of lint on a change whose Kotlin compile takes 1.5s - and nothing is
            // ever installed from debug anyway: device tests and every published APK come from
            // assembleRelease. -PminifyDebug=true brings the old behaviour back for the rare
            // check that something only reproduces under R8.
            //
            // Debuggable for the same reason, and it buys more than a debugger: AGP treats a
            // non-debuggable variant as release-grade and runs lintVital on it, which is another
            // 39s on a build whose Kotlin compile is 1.5s. Lint still gates the release path,
            // where it belongs and where it has already caught a real one.
            isDebuggable = !minifyDebug
            isMinifyEnabled = minifyDebug
            isShrinkResources = minifyDebug
            if (!minifyDebug) {
                // hudlib.aar carries libgojni.so once per ABI (8.5-9.2MB each) and ML Kit adds
                // another per-ABI library: four ABIs is 127MB of native libraries merged and
                // stripped on every debug build, for hardware nobody here owns. The release
                // variant has been arm64-only for the same reason.
                ndk {
                    abiFilters.clear()
                    abiFilters.add("arm64-v8a")
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            // Published APKs carry one architecture. Since 2019 every new chipset is arm64, so
            // even at minSdk 31 the realistic install base is arm64 - x86/x86_64 exist only for
            // Intel emulators, and armeabi-v7a for pre-2019 hardware we deliberately leave out.
            // Shipping all four put ~50MB of native libraries no rider can execute into every
            // download (libmaplibre.so alone was 15MB per ABI). The debug variant keeps every
            // ABI, so emulators still work.
            ndk {
                abiFilters.clear()
                abiFilters.add("arm64-v8a")
            }
            // Keep normal releases protected, but allow a local device-test release
            // with readable stack traces and no R8 obfuscation.
            // Use -PnoReleaseObfuscation=true for the latter.
            isMinifyEnabled = !noReleaseObfuscation
            isShrinkResources = !noReleaseObfuscation
            signingConfig = signingConfigs.findByName("localRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

val androidAutoIdentityDir = rootProject.projectDir.resolve("../../tooling/private/android-auto")
val androidAutoIdentityOutputDir = layout.buildDirectory.dir("generated/res/android-auto-identity/main")
val translationResourceOutputDir = layout.buildDirectory.dir("generated/res/translations/main")
val includeAndroidAutoIdentity = providers.gradleProperty("includeAndroidAutoIdentity")
    .map(String::toBoolean)
    .orElse(false)

val cleanAndroidAutoIdentity by tasks.registering(Delete::class) {
    delete(androidAutoIdentityOutputDir)
    // A Delete with no declared output is never UP-TO-DATE, and this one sits in preBuild and
    // deletes a res.srcDir - so every build dirtied a resource-merge input and could drag a full
    // Kotlin recompile behind it with no source change at all. The purge still happens whenever
    // there is anything to purge, which is all it was ever for.
    onlyIf { androidAutoIdentityOutputDir.get().asFile.exists() }
}

val prepareAndroidAutoIdentity by tasks.registering(Copy::class) {
    dependsOn(cleanAndroidAutoIdentity)
    from(androidAutoIdentityDir) {
        include("aa_cert", "aa_identity_data")
    }
    into(androidAutoIdentityOutputDir.map { it.dir("raw") })
    onlyIf {
        includeAndroidAutoIdentity.get() &&
            androidAutoIdentityDir.resolve("aa_cert").isFile &&
            androidAutoIdentityDir.resolve("aa_identity_data").isFile
    }
    doLast {
        val rawDir = androidAutoIdentityOutputDir.get().dir("raw").asFile
        rawDir.mkdirs()
        rawDir.resolve("motohub_android_auto_identity_keep.xml").writeText(
            """
            <resources xmlns:tools="http://schemas.android.com/tools"
                tools:keep="@raw/aa_cert,@raw/aa_identity_data" />
            """.trimIndent()
        )
    }
}

/**
 * Translator-friendly catalogues live in /translations and use explicit tags
 * such as strings-it-IT.xml. Android resource folders use a different syntax,
 * so map the filenames here before resource processing starts.
 */
val syncTranslationResources by tasks.registering {
    val sourceDir = rootProject.projectDir.resolve("../../translations")
    val outputDir = translationResourceOutputDir.get().asFile
    inputs.dir(sourceDir)
    outputs.dir(outputDir)
    doLast {
        outputDir.deleteRecursively()
        val localeDirectories = mapOf(
            "en-US" to "values",
            "it-IT" to "values-it",
            "pt-PT" to "values-pt-rPT",
            "ko-KR" to "values-ko-rKR",
            "fr-FR" to "values-fr",
            "es-ES" to "values-es",
            "de-DE" to "values-de",
            "nl-NL" to "values-nl"
        )
        sourceDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("strings-") && it.name.endsWith(".xml") }
            ?.forEach { catalog ->
                val locale = catalog.name.removePrefix("strings-").removeSuffix(".xml")
                val resourceDirectory = localeDirectories[locale]
                    ?: error("Unknown translation locale in ${catalog.name}")
                outputDir.resolve(resourceDirectory).mkdirs()
                catalog.copyTo(
                    outputDir.resolve(resourceDirectory).resolve("strings.xml"),
                    overwrite = true
                )
            }
    }
}

android.sourceSets.getByName("main").res.srcDir(androidAutoIdentityOutputDir)
android.sourceSets.getByName("main").res.srcDir(translationResourceOutputDir)
tasks.named("preBuild").configure {
    dependsOn(prepareAndroidAutoIdentity)
    dependsOn(syncTranslationResources)
}

val exportPublicApk by tasks.registering(Copy::class) {
    // Only obfuscated release builds are published, so this is the release variant - never the
    // "debug" buildType it used to copy. That variant is signed with the local debug key, which
    // cannot update a release-signed install: on a phone already running MOTO-HUB it failed with
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE, so it could never have been a real upgrade path.
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.projectDir.resolve("../../artifacts"))
    rename { "MOTO-HUB-${android.defaultConfig.versionName}-${android.defaultConfig.versionCode}-public.apk" }
    doFirst {
        check(hasLocalReleaseSigning) {
            "The persistent MOTO-HUB release keystore and release-signing.properties are required."
        }
        check(!noReleaseObfuscation) {
            "Published artifacts must be obfuscated: drop -PnoReleaseObfuscation to export."
        }
        // This task copies whatever assembleRelease last produced. When that assemble ran with
        // -PincludeAndroidAutoIdentity=true the copy silently carries res/raw/aa_cert and
        // aa_identity_data - the private Android Auto identity - into an artifact whose whole
        // purpose is to be published. Refuse to export rather than trust the invocation.
        check(!includeAndroidAutoIdentity.get()) {
            "The public APK must be assembled WITHOUT -PincludeAndroidAutoIdentity: rerun " +
                "'./gradlew exportPublicApk' with no identity flag so the release is rebuilt clean."
        }
    }
    doLast {
        val exported = destinationDir.resolve(
            "MOTO-HUB-${android.defaultConfig.versionName}-${android.defaultConfig.versionCode}-public.apk"
        )
        // Belt and braces: verify the bytes that were actually copied, so a stale or
        // hand-placed APK can never be published with the identity inside it.
        val identityEntries = zipTree(exported).matching { include("res/raw/aa_cert", "res/raw/aa_identity_data") }
        check(identityEntries.isEmpty) {
            "${exported.name} contains the private Android Auto identity and must not be " +
                "published. Delete it, then rerun the export without -PincludeAndroidAutoIdentity."
        }
    }
}

val exportPrivateAndroidAutoApk by tasks.registering(Copy::class) {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.projectDir.resolve("../../artifacts"))
    rename { "MOTO-HUB-${android.defaultConfig.versionName}-${android.defaultConfig.versionCode}-android-auto-private.apk" }
    doFirst {
        check(hasLocalReleaseSigning) {
            "The persistent MOTO-HUB release keystore and release-signing.properties are required."
        }
        check(!noReleaseObfuscation) {
            "Published artifacts must be obfuscated: drop -PnoReleaseObfuscation to export."
        }
        check(includeAndroidAutoIdentity.get()) {
            "Private Android Auto APK export requires -PincludeAndroidAutoIdentity=true."
        }
        check(
            androidAutoIdentityDir.resolve("aa_cert").isFile &&
                androidAutoIdentityDir.resolve("aa_identity_data").isFile
        ) {
            "The private Android Auto identity files are required."
        }
    }
}

dependencies {
    implementation("com.github.megatronking.stringfog:xor:5.0.0")
    implementation(project(":ipc-contract"))
    // hudlib.aar is GPL-3.0 (the EasyConn/T-Box transport) and is required by CORE.
    implementation(files("libs/hudlib.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.barcode.scanning)
    implementation(libs.zxing.core)
    implementation(libs.protobuf.java)
    implementation(libs.conscrypt.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.sentry.android)

    testImplementation(libs.junit)
    // See the comment on `json` in libs.versions.toml: android.jar's org.json throws on every
    // call under unit tests, so the real implementation is needed to test any JSON parser.
    testImplementation(libs.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
