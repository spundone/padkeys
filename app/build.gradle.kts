import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Release signing: put storeFile / storePassword / keyAlias / keyPassword in keystore.properties (git-ignored;
// CI writes it from secrets). Without it, release builds come out unsigned: never publish those.
val releaseProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = releaseProps.getProperty("storeFile") != null

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.padkeys"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.padkeys"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = rootProject.file(releaseProps.getProperty("storeFile"))
            storePassword = releaseProps.getProperty("storePassword")
            keyAlias = releaseProps.getProperty("keyAlias")
            keyPassword = releaseProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // Signed with the default Android debug key. Own package and label, so a debug build installs
            // next to a release one and both can be enabled as keyboards.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "PadKeys Debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["appLabel"] = "PadKeys"
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.all { it.testLogging { events("passed", "failed"); showStandardStreams = true } } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    // Material 3 Expressive APIs (MaterialExpressiveTheme, ButtonGroup…), same versions as PadBridge.
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    testImplementation("junit:junit:4.13.2")
}
