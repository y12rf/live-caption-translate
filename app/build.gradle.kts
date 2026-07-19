plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.livetranslate"
    compileSdk = 34
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.example.livetranslate"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.2.1-beta2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ABI list lives only in splits.abi (ndk.abiFilters conflicts with splits).
    }

    // Phone arm64 only (CI ships this). Emulator x86: add "x86" here and rebuild locally.
    // Do not also set defaultConfig.ndk.abiFilters — AGP rejects both together.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload-ready release; replace with a real upload keystore for Play Store.
            signingConfig = signingConfigs.getByName("debug")
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
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// android-vad:silero pulls Kotlin 2.x stdlib; project compiler is 1.9.x — pin stdlib.
configurations.configureEach {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlin:kotlin-stdlib:1.9.24",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.24",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.24",
            "org.jetbrains.kotlin:kotlin-stdlib-common:1.9.24"
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Community rebuild of retired arthenica ffmpeg-kit (audio package, FFmpeg 8.x)
    implementation("com.antonkarpenko:ffmpeg-kit-audio:2.2.1")

    // Silero VAD (ONNX) — frame speech/noise classifier for utterance cuts.
    // 2.0.10 is built with Kotlin 2.2 metadata (incompatible with this project's 1.9.x).
    implementation("com.github.gkonovalov.android-vad:silero:2.0.6")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}
