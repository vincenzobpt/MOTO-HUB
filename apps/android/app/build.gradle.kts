plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.motohub.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.motohub.android"
        minSdk = 34
        targetSdk = 36
        versionCode = 38
        versionName = "0.8.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

val prepareHudStaticSignal by tasks.registering(Copy::class) {
    from(rootProject.projectDir.resolve("../../tooling/assets/static_signal.h264"))
    into(layout.buildDirectory.dir("generated/res/hud-static/main/raw"))
    rename { "static_signal.h264" }
}

val androidAutoIdentityDir = rootProject.projectDir.resolve("../../tooling/private/android-auto")
val androidAutoIdentityOutputDir = layout.buildDirectory.dir("generated/res/android-auto-identity/main")
val includeAndroidAutoIdentity = providers.gradleProperty("includeAndroidAutoIdentity")
    .map(String::toBoolean)
    .orElse(false)

val cleanAndroidAutoIdentity by tasks.registering(Delete::class) {
    delete(androidAutoIdentityOutputDir)
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
}

android.sourceSets.getByName("main").res.srcDir(
    layout.buildDirectory.dir("generated/res/hud-static/main")
)
android.sourceSets.getByName("main").res.srcDir(androidAutoIdentityOutputDir)
tasks.named("preBuild").configure {
    dependsOn(prepareHudStaticSignal, prepareAndroidAutoIdentity)
}

val exportPublicApk by tasks.registering(Copy::class) {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.projectDir.resolve("../../artifacts"))
    rename { "MOTO-HUB-${android.defaultConfig.versionName}-${android.defaultConfig.versionCode}-public.apk" }
}

val exportPrivateAndroidAutoApk by tasks.registering(Copy::class) {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.projectDir.resolve("../../artifacts"))
    rename { "MOTO-HUB-${android.defaultConfig.versionName}-${android.defaultConfig.versionCode}-android-auto-private.apk" }
}

dependencies {
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
    implementation(libs.protobuf.java)
    implementation(libs.conscrypt.android)

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
