import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Google push (FCM) is opt-in: without android/app/google-services.json the app still builds; it
// simply cannot use that wake-up channel and the Settings switch stays inert.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")


// Release signing comes from android/keystore.properties (git-ignored). When absent, the
// release variant is left unsigned; debug builds are unaffected.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "io.ofloo.pagerapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.ofloo.pagerapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 112
        versionName = "0.1.112"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Bouncy Castle ships duplicate OSGI manifests and licence files across its jars.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.google.firebase:firebase-messaging:26.0.0")
    // Firebase drags in androidx.fragment 1.0.0 transitively; registerForActivityResult needs at
    // least 1.3.0, so pin a current one (lint fails the release build otherwise).
    implementation("androidx.fragment:fragment:1.8.5")
    implementation(project(":shared"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("org.unifiedpush.android:connector:3.3.5")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
