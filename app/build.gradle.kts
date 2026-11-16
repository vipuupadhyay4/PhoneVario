import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.paravario.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.paravario.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0-phase1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // Phase 0 has no runtime dependencies beyond the Kotlin stdlib.
    testImplementation("junit:junit:4.13.2")
}
