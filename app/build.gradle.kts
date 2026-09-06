plugins {
    id("com.android.application")
}

val releaseSigningValues = mapOf(
    "storeFile" to System.getenv("TUNNELHTTPS_STORE_FILE"),
    "storePassword" to System.getenv("TUNNELHTTPS_STORE_PASSWORD"),
    "keyAlias" to System.getenv("TUNNELHTTPS_KEY_ALIAS"),
    "keyPassword" to System.getenv("TUNNELHTTPS_KEY_PASSWORD")
)
val configuredReleaseSigningValues = releaseSigningValues.values.filterNotNull().filter { it.isNotBlank() }
if (configuredReleaseSigningValues.isNotEmpty() &&
    configuredReleaseSigningValues.size != releaseSigningValues.size
) {
    throw GradleException("Release signing environment variables are only partially configured.")
}
val releaseSigningConfigured = configuredReleaseSigningValues.size == releaseSigningValues.size
val releaseKeystoreFile = releaseSigningValues.getValue("storeFile")?.let(rootProject::file)
if (releaseSigningConfigured && releaseKeystoreFile?.isFile != true) {
    throw GradleException("TUNNELHTTPS_STORE_FILE does not reference a readable keystore.")
}

android {
    namespace = "com.tunnelvpn.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tunnelvpn.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 11
        versionName = "0.9.0-beta.1"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = releaseKeystoreFile
                storePassword = releaseSigningValues.getValue("storePassword")
                keyAlias = releaseSigningValues.getValue("keyAlias")
                keyPassword = releaseSigningValues.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".turbo"
            versionNameSuffix = "-turbo"
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (releaseSigningConfigured) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxParallelForks = 1
    maxHeapSize = "256m"
    jvmArgs(
        "-Xss512k",
        "-XX:HeapBaseMinAddress=4g",
        "-XX:ReservedCodeCacheSize=96m",
        "-XX:TieredStopAtLevel=1",
        "-XX:CICompilerCount=2"
    )
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.webkit:webkit:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
