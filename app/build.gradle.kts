import com.android.build.api.dsl.Packaging

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    packaging {
        resources {
            // List of exclusions
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/INDEX.LIST"
            )
        }
    }

    namespace = "com.example.recordaudio1"
    compileSdk = 35


    defaultConfig {
        applicationId = "com.example.recordaudio1"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        // This enables BuildConfig generation
        buildConfig = true
        viewBinding = true
    }
    buildTypes {
        debug {
            buildConfigField("boolean", "SAVE_AUDIO_FILES", "false")
        }
        release {
            buildConfigField("boolean", "SAVE_AUDIO_FILES", "false")
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    sourceSets {
        getByName("main") {
            assets {
                srcDirs("src/main/assets")
            }
        }
    }
    dependencies {
        implementation("androidx.core:core-ktx:1.12.0")  // Use appropriate version
        implementation("androidx.appcompat:appcompat:1.6.1")
        implementation("com.google.android.material:material:1.11.0")
        implementation("androidx.activity:activity:1.8.2")
        implementation("androidx.constraintlayout:constraintlayout:2.1.4")
        implementation(files("libs/JWave.jar"))
        implementation("com.google.firebase:firebase-appdistribution-gradle:4.0.1")
        testImplementation("junit:junit:4.13.2")
        androidTestImplementation("androidx.test.ext:junit:1.1.5")
        androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

        // Google Cloud Speech API
        implementation("com.google.cloud:google-cloud-speech:2.5.0")
        implementation("com.google.protobuf:protobuf-java:3.19.4")
        implementation("io.grpc:grpc-okhttp:1.46.0")
        implementation("io.grpc:grpc-stub:1.46.0")
        implementation("io.grpc:grpc-auth:1.46.0")
        implementation("com.google.auth:google-auth-library-oauth2-http:1.6.0")

    }
}
