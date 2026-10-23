import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Only the private file path is configurable here; secrets never enter project properties.
val releaseSigningPath = providers.environmentVariable("LIFELOG_SIGNING_PROPERTIES")
    .orElse(providers.gradleProperty("lifelog.signingProperties")).orNull
val releaseSigningFile = releaseSigningPath?.let { file(it) }
val releaseSecrets = Properties().apply {
    if (releaseSigningFile?.isFile == true) releaseSigningFile.inputStream().use { load(it) }
}
val hasReleaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !releaseSecrets.getProperty(it).isNullOrBlank() }
val requestedVersionCode = providers.gradleProperty("releaseVersionCode").orElse("2").get().toInt()
val requestedVersionName = providers.gradleProperty("releaseVersionName").orElse("0.1.1").get()
require(requestedVersionCode > 0) { "versionCode must be positive" }
require(requestedVersionName.matches(Regex("\\d+\\.\\d+\\.\\d+([-.][A-Za-z0-9.-]+)?"))) { "Invalid versionName" }

android {
    namespace = "com.personal.lifelog"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.personal.lifelog"
        minSdk = 28
        targetSdk = 36
        versionCode = requestedVersionCode
        versionName = requestedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    if (hasReleaseSigning) {
        signingConfigs.create("localRelease") {
            storeFile = file(releaseSecrets.getProperty("storeFile"))
            storePassword = releaseSecrets.getProperty("storePassword")
            keyAlias = releaseSecrets.getProperty("keyAlias")
            keyPassword = releaseSecrets.getProperty("keyPassword")
            storeType = "PKCS12"
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("localRelease")
        }
        create("qaRelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    doLast {
        check(hasReleaseSigning && releaseSecrets.getProperty("storeFile")?.let { file(it).isFile } == true) {
            "Release signing is missing. Set LIFELOG_SIGNING_PROPERTIES to the private signing.properties file."
        }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(requireReleaseSigning)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Navigation otherwise packages an older core in the target APK; Android loads it
    // ahead of Room migration-test serializers, causing an AbstractMethodError.
    implementation(libs.kotlinx.serialization.core)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Compose's transitive Espresso 3.5.0 uses a hidden InputManager API removed in API 37.
    androidTestImplementation(libs.androidx.espresso.core)
}
