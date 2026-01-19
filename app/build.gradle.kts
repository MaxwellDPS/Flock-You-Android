plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.flockyou"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.flockyou"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "com.flockyou.HiltTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Default build config values (can be overridden by flavors)
        buildConfigField("boolean", "IS_SYSTEM_BUILD", "false")
        buildConfigField("boolean", "IS_OEM_BUILD", "false")
        buildConfigField("String", "BUILD_MODE", "\"sideload\"")
    }

    // Product flavors for different installation modes
    flavorDimensions += "installMode"
    productFlavors {
        // Standard sideload version (Play Store, APK install) - set as default
        create("sideload") {
            isDefault = true
            dimension = "installMode"
            applicationIdSuffix = ""
            versionNameSuffix = ""

            buildConfigField("boolean", "IS_SYSTEM_BUILD", "false")
            buildConfigField("boolean", "IS_OEM_BUILD", "false")
            buildConfigField("String", "BUILD_MODE", "\"sideload\"")

            // Standard manifest - no special OEM configurations
            manifestPlaceholders["appLabel"] = "@string/app_name"
        }

        // System privileged app version (installed in /system/priv-app)
        create("system") {
            dimension = "installMode"
            applicationIdSuffix = ""
            versionNameSuffix = "-system"

            buildConfigField("boolean", "IS_SYSTEM_BUILD", "true")
            buildConfigField("boolean", "IS_OEM_BUILD", "false")
            buildConfigField("String", "BUILD_MODE", "\"system\"")

            // System app label suffix for identification
            manifestPlaceholders["appLabel"] = "@string/app_name_system"
        }

        // OEM embedded version (signed with platform certificate)
        create("oem") {
            dimension = "installMode"
            applicationIdSuffix = ""
            versionNameSuffix = "-oem"

            buildConfigField("boolean", "IS_SYSTEM_BUILD", "true")
            buildConfigField("boolean", "IS_OEM_BUILD", "true")
            buildConfigField("String", "BUILD_MODE", "\"oem\"")

            // OEM app label suffix
            manifestPlaceholders["appLabel"] = "@string/app_name_oem"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
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
        buildConfig = true  // Enable BuildConfig for debug checks
    }
    // Note: composeOptions.kotlinCompilerExtensionVersion is not needed with Kotlin 2.0+
    // The compose compiler is now a Kotlin compiler plugin (org.jetbrains.kotlin.plugin.compose)
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            keepDebugSymbols += listOf(
                "*/libllm_inference_engine_jni.so",
                "*/libsqlcipher.so",
                "*/libtensorflowlite_gpu_jni.so",
                "*/libtensorflowlite_jni.so"
            )
        }
    }

    // Ensure all build variants are visible in Android Studio
    // This creates: sideloadDebug, sideloadRelease, systemDebug, systemRelease, oemDebug, oemRelease
    androidComponents {
        beforeVariants { variantBuilder ->
            // All variants are enabled by default - no filtering
            // Debug variants: oemDebug, sideloadDebug, systemDebug
            // Release variants: oemRelease, sideloadRelease, systemRelease
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    
    // Compose
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material") // For pull-to-refresh
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    
    // Hilt
    implementation("com.google.dagger:hilt-android:2.54")
    ksp("com.google.dagger:hilt-android-compiler:2.54")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    
    // Room with SQLCipher encryption
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:sqlcipher-android:4.12.0@aar")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    
    // Location
    implementation("com.google.android.gms:play-services-location:21.1.0")
    
    // Maps - OpenStreetMap (no API key required)
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    
    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    
    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // WorkManager for periodic background tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.hilt:hilt-work:1.1.0")
    ksp("androidx.hilt:hilt-compiler:1.1.0")

    // Guava for ListenableFuture (needed by WorkManager awaits)
    implementation("com.google.guava:guava:32.1.3-android")

    // OkHttp for HTTP downloads with TLS support
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-tls:4.12.0")

    // USB Serial for Flipper Zero USB CDC communication
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")

    // Security - Encrypted SharedPreferences for storing DB passphrase
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Biometric authentication
    implementation("androidx.biometric:biometric:1.1.0")

    // Android Auto - Car App Library (optional)
    implementation("androidx.car.app:app:1.4.0")

    // Google AI - On-device inference (LOCAL ONLY - no cloud API)
    // LiteRT for GGUF model inference
    implementation("com.google.ai.edge.litert:litert:1.0.1")
    implementation("com.google.ai.edge.litert:litert-gpu:1.0.1")
    implementation("com.google.ai.edge.litert:litert-support:1.0.1")

    // MediaPipe LLM Inference for GGUF models on-device
    implementation("com.google.mediapipe:tasks-genai:0.10.22")

    // ML Kit GenAI Prompt API for Gemini Nano on-device inference (Alpha)
    // This provides access to the on-device Gemini Nano model via AICore
    // Requires Pixel 8+ or compatible device with Android 14+
    // Note: Alpha API - not subject to SLA or deprecation policy
    implementation("com.google.mlkit:genai-prompt:1.0.0-alpha1")
    
    // Testing - Unit Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("io.mockk:mockk:1.13.8")
    
    // Testing - Instrumented Tests
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("io.mockk:mockk-android:1.13.8")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // Hilt Testing
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.54")
    kspAndroidTest("com.google.dagger:hilt-android-compiler:2.54")
    
    // Debug implementations
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ================================================================
// OUI Database Update Task
// ================================================================

/**
 * Task to download/refresh the IEEE OUI database CSV for bundled assets.
 * This ensures the app ships with an up-to-date manufacturer database.
 *
 * Run manually: ./gradlew updateOuiDatabase
 * Runs automatically before release builds.
 */
tasks.register("updateOuiDatabase") {
    group = "assets"
    description = "Downloads the latest IEEE OUI database for bundled assets"

    val ouiUrl = "https://standards-oui.ieee.org/oui/oui.csv"
    val assetsDir = file("src/main/assets")
    val ouiFile = file("src/main/assets/oui.csv")

    doLast {
        // Create assets directory if it doesn't exist
        if (!assetsDir.exists()) {
            assetsDir.mkdirs()
            println("Created assets directory: ${assetsDir.absolutePath}")
        }

        println("Downloading OUI database from IEEE...")
        try {
            val url = uri(ouiUrl).toURL()
            val connection = url.openConnection() as javax.net.ssl.HttpsURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "text/csv")
            connection.setRequestProperty("User-Agent", "FlockYou-Build/1.0")
            connection.connectTimeout = 30000
            connection.readTimeout = 60000

            if (connection.responseCode == 200) {
                connection.inputStream.use { input ->
                    ouiFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val lineCount = ouiFile.readLines().size
                println("Successfully downloaded OUI database: ${ouiFile.length() / 1024} KB, $lineCount entries")
            } else {
                println("Warning: Failed to download OUI database (HTTP ${connection.responseCode})")
                println("Using existing bundled database if available")
                if (!ouiFile.exists()) {
                    throw GradleException("No OUI database available and download failed")
                }
            }
        } catch (e: Exception) {
            println("Warning: Could not download OUI database: ${e.message}")
            if (!ouiFile.exists()) {
                throw GradleException("No OUI database available: ${e.message}")
            }
            println("Using existing bundled database")
        }
    }
}

// Hook OUI update into release builds
tasks.matching { it.name.contains("Release") && it.name.startsWith("assemble") }.configureEach {
    dependsOn("updateOuiDatabase")
}

// ================================================================
// Flipper Zero FAP Build Tasks
// ================================================================

/**
 * Configuration for Flipper Zero FAP building.
 * The FAP (Flipper Application Package) is built using the ufbt tool.
 *
 * Prerequisites:
 * - ufbt installed: pip install ufbt
 * - FLIPPER_FIRMWARE_PATH environment variable (optional)
 */
val flipperAppDir = file("${rootDir}/flipper_app/flock_bridge")
val flipperBuildDir = file("${buildDir}/flipper")
val fapOutputName = "flock_bridge.fap"

/**
 * Check if ufbt (micro Flipper Build Tool) is available.
 */
tasks.register("checkUfbt") {
    group = "flipper"
    description = "Check if ufbt is installed"

    doLast {
        try {
            val result = exec {
                commandLine("ufbt", "--version")
                isIgnoreExitValue = true
            }
            if (result.exitValue != 0) {
                throw GradleException("ufbt not found. Install with: pip install ufbt")
            }
            println("ufbt found")
        } catch (e: Exception) {
            throw GradleException("ufbt not available: ${e.message}\nInstall with: pip install ufbt")
        }
    }
}

/**
 * Build the Flipper FAP using ufbt.
 * Run: ./gradlew buildFlipperFap
 */
tasks.register("buildFlipperFap") {
    group = "flipper"
    description = "Build the Flock Bridge FAP for Flipper Zero"
    dependsOn("checkUfbt")

    inputs.dir(flipperAppDir)
    outputs.file(file("${flipperBuildDir}/${fapOutputName}"))

    doLast {
        // Create build directory
        flipperBuildDir.mkdirs()

        println("Building Flock Bridge FAP...")
        println("Source: ${flipperAppDir.absolutePath}")

        exec {
            workingDir = flipperAppDir
            commandLine(
                "ufbt",
                "fap_flock_bridge",
                "COMPACT=1",
                "DEBUG=0"
            )
        }

        // Copy the built FAP to our build directory
        val distDir = file("${flipperAppDir}/dist")
        val builtFap = fileTree(distDir).matching {
            include("**/*.fap")
        }.singleFile

        copy {
            from(builtFap)
            into(flipperBuildDir)
            rename { fapOutputName }
        }

        println("FAP built successfully: ${flipperBuildDir}/${fapOutputName}")
    }
}

/**
 * Clean Flipper build artifacts.
 */
tasks.register("cleanFlipperFap") {
    group = "flipper"
    description = "Clean Flipper FAP build artifacts"

    doLast {
        exec {
            workingDir = flipperAppDir
            commandLine("ufbt", "clean")
            isIgnoreExitValue = true
        }
        delete(flipperBuildDir)
        println("Flipper build artifacts cleaned")
    }
}

/**
 * Copy the FAP to Android assets for bundling with the app.
 * This allows the Android app to install the FAP to connected Flipper devices.
 */
tasks.register("bundleFlipperFap") {
    group = "flipper"
    description = "Bundle the FAP in Android assets for in-app installation"
    dependsOn("buildFlipperFap")

    val assetsFlipperDir = file("src/main/assets/flipper")
    val targetFap = file("${assetsFlipperDir}/${fapOutputName}")

    inputs.file(file("${flipperBuildDir}/${fapOutputName}"))
    outputs.file(targetFap)

    doLast {
        assetsFlipperDir.mkdirs()

        copy {
            from(file("${flipperBuildDir}/${fapOutputName}"))
            into(assetsFlipperDir)
        }

        // Also copy the ESP32 firmware for in-app flashing
        val esp32FirmwareDir = file("${rootDir}/flipper_app/esp32_firmware")
        if (esp32FirmwareDir.exists()) {
            copy {
                from(esp32FirmwareDir)
                into(file("${assetsFlipperDir}/esp32"))
                include("*.ino", "*.h", "*.cpp")
            }
            println("ESP32 firmware copied to assets")
        }

        println("FAP bundled in assets: ${targetFap.absolutePath}")
        println("Size: ${targetFap.length() / 1024} KB")
    }
}

/**
 * Install FAP to connected Flipper via qFlipper CLI.
 * Requires qFlipper to be installed with CLI tools.
 */
tasks.register("installFlipperFap") {
    group = "flipper"
    description = "Install FAP to connected Flipper Zero via USB"
    dependsOn("buildFlipperFap")

    doLast {
        val fapFile = file("${flipperBuildDir}/${fapOutputName}")

        println("Installing FAP to Flipper Zero...")

        // Try using qFlipper CLI
        try {
            exec {
                commandLine(
                    "qFlipper-cli",
                    "storage", "write",
                    fapFile.absolutePath,
                    "/ext/apps/Tools/${fapOutputName}"
                )
            }
            println("FAP installed successfully!")
            println("Location: /ext/apps/Tools/${fapOutputName}")
        } catch (e: Exception) {
            // Try ufbt's built-in launch feature
            println("qFlipper CLI not found, trying ufbt...")
            exec {
                workingDir = flipperAppDir
                commandLine("ufbt", "launch")
            }
        }
    }
}

/**
 * Full Flipper workflow: build, bundle, and optionally install.
 */
tasks.register("prepareFlipperFap") {
    group = "flipper"
    description = "Build and bundle the FAP for distribution"
    dependsOn("bundleFlipperFap")

    doLast {
        println("")
        println("=== Flipper FAP Ready ===")
        println("FAP file: ${flipperBuildDir}/${fapOutputName}")
        println("Bundled in: src/main/assets/flipper/${fapOutputName}")
        println("")
        println("To install manually:")
        println("  1. Connect Flipper Zero via USB")
        println("  2. Run: ./gradlew installFlipperFap")
        println("")
        println("Or use the in-app Flipper installer")
    }
}

// Automatically bundle FAP for release builds
tasks.matching { it.name.contains("Release") && it.name.startsWith("assemble") }.configureEach {
    dependsOn("bundleFlipperFap")
}
