import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing material (never commit secrets):
//  1. gitignored keystore.properties at the repo root (local/Hetzner):
//     storeFile / storePassword / keyAlias / keyPassword
//  2. ANDROID_RELEASE_* env vars (CI): KEYSTORE_BASE64, STORE_PASSWORD,
//     KEY_ALIAS, KEY_PASSWORD
// Without either source, packageRelease fails closed (no unsigned release APK).
val repoRoot = rootProject.projectDir

val releaseSigning: Map<String, String>? = run {
    val repoProps = File(repoRoot, "keystore.properties")
    val homeProps = File(System.getProperty("user.home"), ".flight-map/keystore.properties")
    val propsFile = if (repoProps.isFile) repoProps else homeProps
    if (propsFile.isFile) {
        val props = Properties()
        propsFile.inputStream().use { stream -> props.load(stream) }
        fun required(key: String): String =
            props.getProperty(key)?.trim()?.takeUnless(String::isEmpty)
                ?: throw GradleException(
                    "keystore.properties is missing '$key' " +
                        "(expected storeFile / storePassword / keyAlias / keyPassword)",
                )
        mapOf(
            "storeFile" to required("storeFile"),
            "storePassword" to required("storePassword"),
            "keyAlias" to required("keyAlias"),
            "keyPassword" to required("keyPassword"),
        )
    } else {
        val envValues = mapOf(
            "ANDROID_RELEASE_KEYSTORE_BASE64" to
                (System.getenv("ANDROID_RELEASE_KEYSTORE_BASE64")?.trim().orEmpty()),
            "ANDROID_RELEASE_STORE_PASSWORD" to
                (System.getenv("ANDROID_RELEASE_STORE_PASSWORD")?.trim().orEmpty()),
            "ANDROID_RELEASE_KEY_ALIAS" to
                (System.getenv("ANDROID_RELEASE_KEY_ALIAS")?.trim().orEmpty()),
            "ANDROID_RELEASE_KEY_PASSWORD" to
                (System.getenv("ANDROID_RELEASE_KEY_PASSWORD")?.trim().orEmpty()),
        )
        if (envValues.values.all(String::isEmpty)) {
            null
        } else {
            val missing = envValues.filterValues(String::isEmpty).keys
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Release signing environment is partial; missing $missing. " +
                        "Set all four ANDROID_RELEASE_* variables or none.",
                )
            }
            val keystoreDir = File(repoRoot, "build/release-signing").apply { mkdirs() }
            val keystoreFile = File(keystoreDir, "flight-map-release.keystore")
            keystoreFile.writeBytes(
                Base64.getDecoder().decode(envValues.getValue("ANDROID_RELEASE_KEYSTORE_BASE64")),
            )
            mapOf(
                "storeFile" to keystoreFile.absolutePath,
                "storePassword" to envValues.getValue("ANDROID_RELEASE_STORE_PASSWORD"),
                "keyAlias" to envValues.getValue("ANDROID_RELEASE_KEY_ALIAS"),
                "keyPassword" to envValues.getValue("ANDROID_RELEASE_KEY_PASSWORD"),
            )
        }
    }
}

android {
    namespace = "com.flightmap.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.flightmap.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                val storePath = releaseSigning.getValue("storeFile")
                val store = File(storePath).let { if (it.isAbsolute) it else File(repoRoot, storePath) }
                if (!store.isFile) {
                    throw GradleException(
                        "release keystore file is missing: $store " +
                            "(check storeFile in keystore.properties)",
                    )
                }
                storeFile = store
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// Fail closed: without signing material, packaging a release APK must fail
// loudly instead of producing an unsigned artifact.
if (releaseSigning == null) {
    val requireReleaseSigningMaterial = tasks.register("requireReleaseSigningMaterial") {
        group = "verification"
        description = "Fails unless release signing material is available."
        doLast {
            throw GradleException(
                "Cannot build a release APK without signing material. " +
                    "Provide a gitignored keystore.properties at the repo root " +
                    "(see README, Release signing) or all four ANDROID_RELEASE_* " +
                    "environment variables.",
            )
        }
    }
    tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" }.configureEach {
        dependsOn(requireReleaseSigningMaterial)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
