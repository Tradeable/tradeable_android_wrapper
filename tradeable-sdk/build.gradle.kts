plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("maven-publish")
}

android {
    namespace = "com.tradeable.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        
        // Build config fields for Flutter SDK configuration
        buildConfigField("String", "FLUTTER_SDK_REPO", "\"${findProperty("FLUTTER_SDK_REPO") ?: "https://github.com/deepakgrandhi/tradeable_flutter_sdk_module.git"}\"")
        buildConfigField("String", "FLUTTER_SDK_BRANCH", "\"${findProperty("FLUTTER_SDK_BRANCH") ?: "main"}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        buildConfig = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    
    // Include Flutter AAR when available
    sourceSets {
        getByName("main") {
            // Flutter release AAR will be included here after build
            if (file("$projectDir/libs/flutter_release.aar").exists()) {
                jniLibs.srcDirs("$projectDir/libs")
            }
        }
    }
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    
    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.8.2")
    
    // Flutter embedding - built locally as part of build.sh
    releaseImplementation("com.tradeable.tradeable_flutter_sdk_module:flutter_release:1")
    debugImplementation("com.tradeable.tradeable_flutter_sdk_module:flutter_debug:1")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Task to publish AAR
tasks.register<Copy>("publishAAR") {
    dependsOn("assembleRelease")
    from("build/outputs/aar/tradeable-sdk-release.aar")
    into("$rootDir/output")
    rename { "tradeable-android-wrapper.aar" }
}

// Publish wrapper AAR to GitHub Packages (Maven).
// Version: pass -PwrapperVersion=1.2.0 (CI derives it from the git tag).
// Credentials: -Pgpr.user=... -Pgpr.key=... or GITHUB_ACTOR/GITHUB_TOKEN env.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "com.tradeable"
                artifactId = "android-wrapper"
                version = findProperty("wrapperVersion")?.toString() ?: "0.0.0-local"
                from(components["release"])
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/Tradeable/tradeable_android_wrapper")
                credentials {
                    username = findProperty("gpr.user")?.toString() ?: System.getenv("GITHUB_ACTOR")
                    password = findProperty("gpr.key")?.toString() ?: System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}

