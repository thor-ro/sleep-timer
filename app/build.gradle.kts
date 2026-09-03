import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing credentials come from a gitignored keystore.properties at the repo root,
// or, when a value is absent there, from the environment. The environment path exists so the
// passwords never have to be written to disk at all:
//
//   KEYSTORE_FILE  KEYSTORE_PASSWORD  KEY_ALIAS  KEY_PASSWORD
//
// If any of the four cannot be resolved the release build stays unsigned rather than silently
// falling back to the debug key, which would stamp the app with a throwaway identity that
// Play rejects and that cannot be used to sign later updates.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(propertyKey: String, environmentKey: String): String? =
    (keystoreProperties.getProperty(propertyKey)
        ?: providers.environmentVariable(environmentKey).orNull)
        ?.takeIf { it.isNotBlank() }

val signingStoreFile = signingValue("storeFile", "KEYSTORE_FILE")
val signingStorePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
val signingKeyAlias = signingValue("keyAlias", "KEY_ALIAS")
val signingKeyPassword = signingValue("keyPassword", "KEY_PASSWORD")
val hasReleaseSigning = signingStoreFile != null && signingStorePassword != null &&
    signingKeyAlias != null && signingKeyPassword != null

// Version is supplied by the release workflow from the git tag (see .github/workflows/release.yml)
// and falls back to these values for ordinary local builds.
val appVersionName = (findProperty("appVersionName") as String?)?.takeIf { it.isNotBlank() } ?: "1.0"
val appVersionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.tlr.sleeptimer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tlr.sleeptimer"
        minSdk = 35
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // rootProject.file, not file(): paths are documented as relative to the repo
                // root in keystore.properties.example, whereas file() would resolve against app/.
                // Absolute paths pass through unchanged.
                storeFile = rootProject.file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // else: no credentials resolved, so leave the release build unsigned rather than
            // failing -- `assembleRelease` still works for anyone who clones the repo.
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
