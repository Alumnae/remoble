plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

val signingStorePath = providers.gradleProperty("REMOBLE_SIGNING_STORE_FILE").orNull
val signingStorePassword = providers.gradleProperty("REMOBLE_SIGNING_STORE_PASSWORD").orNull
val signingKeyAlias = providers.gradleProperty("REMOBLE_SIGNING_KEY_ALIAS").orNull
val signingKeyPassword = providers.gradleProperty("REMOBLE_SIGNING_KEY_PASSWORD").orNull
val hasExternalSigning =
    listOf(signingStorePath, signingStorePassword, signingKeyAlias, signingKeyPassword)
        .all { !it.isNullOrBlank() }

android {
    namespace = "ee.nekoko.remoble"
    compileSdk = 34

    defaultConfig {
        applicationId = "ee.nekoko.remoble"
        minSdk = 21 // Support older devices with SimAlliance
        targetSdk = 34
        versionCode = 15
        versionName = "0.3.3"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (hasExternalSigning) {
            create("remoble") {
                storeFile = file(signingStorePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("remoble")?.let { signingConfig = it }
        }
        debug {
            signingConfigs.findByName("remoble")?.let { signingConfig = it }
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    
    // Ktor Server
    val ktorVersion = "2.3.7"
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    testImplementation("junit:junit:4.13.2")
}
