plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("maven-publish")
}

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    // Compose compiler is managed by org.jetbrains.kotlin.plugin.compose
    // (do not pin kotlinCompilerExtensionVersion with Kotlin 2.x).
    
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Kotlin (kept in step with the Kotlin Gradle plugin in the root build file)
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
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

// Publish the SLIM AAR to GitHub Packages (Maven) — and only the slim.
// Rationale: the thin component build's POM points at the Flutter module
// Maven repo, which exists solely on the build machine, so Maven consumers
// could never resolve it. The slim AAR is self-contained (wrapper + engine +
// Dart + plugins); its POM therefore declares only public, anonymously
// resolvable libraries. Consumers need just:
//     implementation("com.tradeable:android-wrapper:<version>")
// plus a Compose host app. Same coordinates as before, working content.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "com.tradeable"
                artifactId = "android-wrapper"
                version = findProperty("wrapperVersion")?.toString() ?: "0.0.0-local"
                artifact(rootDir.resolve("output/tradeable-android-wrapper-slim.aar")) {
                    extension = "aar"
                }
                pom {
                    name.set("Tradeable Android Wrapper (slim, self-contained)")
                    description.set(
                        "Tradeable Flutter SDK wrapper for native Android. " +
                            "Self-contained: Flutter engine, Dart code and plugins are bundled, " +
                            "no extra Flutter lines/repos needed."
                    )
                    withXml {
                        val dependencies = asNode().appendNode("dependencies")
                        fun dep(group: String, name: String, version: String) {
                            val node = dependencies.appendNode("dependency")
                            node.appendNode("groupId", group)
                            node.appendNode("artifactId", name)
                            node.appendNode("version", version)
                            node.appendNode("scope", "runtime")
                        }
                        dep("org.jetbrains.kotlinx", "kotlinx-coroutines-android", "1.7.3")
                        dep("androidx.core", "core-ktx", "1.12.0")
                        dep("androidx.appcompat", "appcompat", "1.6.1")
                        dep("androidx.lifecycle", "lifecycle-runtime-ktx", "2.7.0")
                        dep("androidx.lifecycle", "lifecycle-viewmodel-compose", "2.7.0")
                        dep("androidx.activity", "activity-compose", "1.8.2")
                        dep("androidx.compose.ui", "ui", "1.6.1")
                        dep("androidx.compose.ui", "ui-graphics", "1.6.1")
                        dep("androidx.compose.foundation", "foundation", "1.6.1")
                        dep("androidx.compose.material3", "material3", "1.2.0")
                    }
                }
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

// The slim AAR is produced by assembleFatAar, not by the standard build —
// make sure publishing can never upload a stale/missing file.
tasks.matching { it.name == "publishReleasePublicationToGitHubPackagesRepository" }.configureEach {
    dependsOn("assembleFatAar")
}

tasks.register("assembleFatAar") {
    dependsOn("assembleRelease")
    group = "build"
    description = "Merges wrapper + Flutter release artifacts into self-contained fat and slim AARs. " +
        "Slim drops androidx.browser/webkit/relinker for consumers whose own app already ships them."
    doLast {
        val wrapperAar = layout.buildDirectory.get().asFile.resolve("outputs/aar/tradeable-sdk-release.aar")
        com.tradeable.fatpack.FatAar.assemble(
            project,
            wrapperAar,
            rootDir.resolve("output/tradeable-android-wrapper-fat.aar"),
            flavor = "fat"
        )
        com.tradeable.fatpack.FatAar.assemble(
            project,
            wrapperAar,
            rootDir.resolve("output/tradeable-android-wrapper-slim.aar"),
            flavor = "slim",
            // Slim drops the libs a consumer provably already ships
            // (proven by `Duplicate class` build errors): their own
            // copies satisfy both sides. Confirmed colliding set:
            // androidx.browser (Custom Tabs), androidx.webkit (WebView
            // compat), relinker (native lib loader).
            excludeModules = setOf(
                "androidx.browser:browser",
                "androidx.webkit:webkit",
                "com.getkeepsafe.relinker:relinker"
            )
        )
    }
}

