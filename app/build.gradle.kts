import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

// Release signing: keystore.properties at repo root (untracked) and/or env vars. See docs/android-release-signing.md
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

fun signingProp(
    propertiesKey: String,
    environmentKey: String,
): String? {
    val fromEnv = System.getenv(environmentKey)?.trim()
    if (!fromEnv.isNullOrEmpty()) return fromEnv
    val fromFile = keystoreProperties.getProperty(propertiesKey)?.trim()
    return if (fromFile.isNullOrEmpty()) null else fromFile
}

val releaseStoreFilePath = signingProp("storeFile", "ROADS_STORE_FILE")
val releaseStorePassword = signingProp("storePassword", "ROADS_STORE_PASSWORD")
val releaseKeyAlias = signingProp("keyAlias", "ROADS_KEY_ALIAS")
val releaseKeyPassword = signingProp("keyPassword", "ROADS_KEY_PASSWORD")

val hasReleaseSigning: Boolean =
    releaseStoreFilePath != null &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null

android {
    namespace = "org.openlgx.roads"
    compileSdk = 35

    defaultConfig {
        val appId = "org.openlgx.roads"
        applicationId = appId
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        val activityRecognitionUpdatesAction = "$appId.ACTION_ACTIVITY_RECOGNITION_UPDATES"
        manifestPlaceholders["activityRecognitionUpdatesAction"] = activityRecognitionUpdatesAction
        buildConfigField(
            "String",
            "ACTIVITY_RECOGNITION_UPDATES_ACTION",
            "\"$activityRecognitionUpdatesAction\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // Path may be absolute or relative to the repository root (not the app/ module).
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

gradle.taskGraph.whenReady {
    val wantsSignedRelease =
        allTasks.any { task ->
            task.name == "assembleRelease" || task.name == "bundleRelease"
        }
    if (wantsSignedRelease && !hasReleaseSigning) {
        throw org.gradle.api.GradleException(
            """
            Release signing is not configured.

            Field-trial release builds must be signed. Do one of the following:

            1) Copy keystore.properties.example to keystore.properties at the repo root,
               fill in storeFile (path to .jks/.keystore), storePassword, keyAlias, keyPassword.
               Never commit keystore.properties or the keystore file.

            2) Set environment variables (overrides the file):
               ROADS_STORE_FILE, ROADS_STORE_PASSWORD, ROADS_KEY_ALIAS, ROADS_KEY_PASSWORD

            See: docs/android-release-signing.md
            """.trimIndent(),
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.location)
    implementation(libs.timber)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
