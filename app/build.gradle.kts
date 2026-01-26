plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// OEM configurable application ID
// OEM partners can set OEM_PACKAGE_NAME in gradle.properties to use their own package name
val oemPackageName: String = project.findProperty("OEM_PACKAGE_NAME")?.toString() ?: "com.flockyou"
val defaultPackageName = "com.flockyou"

// ================================================================
// OEM Feature Flags Configuration
// ================================================================
// Read feature flags from gradle.properties (can be overridden via command line)
// Example: ./gradlew assembleOemRelease -POEM_FEATURE_FLIPPER_ENABLED=false

fun getOemFeatureFlag(name: String, defaultValue: Boolean = true): Boolean {
    return project.findProperty(name)?.toString()?.toBoolean() ?: defaultValue
}

val oemFeatureFlipperEnabled = getOemFeatureFlag("OEM_FEATURE_FLIPPER_ENABLED")
val oemFeatureUltrasonicEnabled = getOemFeatureFlag("OEM_FEATURE_ULTRASONIC_ENABLED")
val oemFeatureAndroidAutoEnabled = getOemFeatureFlag("OEM_FEATURE_ANDROID_AUTO_ENABLED")
val oemFeatureNukeEnabled = getOemFeatureFlag("OEM_FEATURE_NUKE_ENABLED")
val oemFeatureAiEnabled = getOemFeatureFlag("OEM_FEATURE_AI_ENABLED")
val oemFeatureTorEnabled = getOemFeatureFlag("OEM_FEATURE_TOR_ENABLED")
val oemFeatureMapEnabled = getOemFeatureFlag("OEM_FEATURE_MAP_ENABLED")

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

        // ================================================================
        // Network URL Configuration (OEM-overridable)
        // ================================================================
        // These URLs can be overridden via gradle.properties for OEM customization.
        // Usage: ./gradlew assembleOemRelease -PURL_GITHUB_REPO="https://custom.repo.com"

        // External Service URLs
        buildConfigField("String", "URL_GITHUB_REPO",
            "\"${project.findProperty("URL_GITHUB_REPO") ?: "https://github.com/MaxwellDPS/Flock-You-Android"}\"")

        // AI Model Download URLs (Hugging Face)
        buildConfigField("String", "URL_AI_MODEL_GEMMA3_1B",
            "\"${project.findProperty("URL_AI_MODEL_GEMMA3_1B") ?: "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"}\"")
        buildConfigField("String", "URL_AI_MODEL_GEMMA_2B_CPU",
            "\"${project.findProperty("URL_AI_MODEL_GEMMA_2B_CPU") ?: "https://huggingface.co/t-ghosh/gemma-tflite/resolve/main/gemma-1.1-2b-it-cpu-int4.bin"}\"")
        buildConfigField("String", "URL_AI_MODEL_GEMMA_2B_GPU",
            "\"${project.findProperty("URL_AI_MODEL_GEMMA_2B_GPU") ?: "https://huggingface.co/t-ghosh/gemma-tflite/resolve/main/gemma2-2b-it-cpu-int8.task"}\"")

        // Map Tile Server URLs (OpenStreetMap)
        buildConfigField("String", "URL_MAP_TILE_A",
            "\"${project.findProperty("URL_MAP_TILE_A") ?: "https://a.tile.openstreetmap.org/"}\"")
        buildConfigField("String", "URL_MAP_TILE_B",
            "\"${project.findProperty("URL_MAP_TILE_B") ?: "https://b.tile.openstreetmap.org/"}\"")
        buildConfigField("String", "URL_MAP_TILE_C",
            "\"${project.findProperty("URL_MAP_TILE_C") ?: "https://c.tile.openstreetmap.org/"}\"")

        // Network Check URLs
        buildConfigField("String", "URL_TOR_CHECK",
            "\"${project.findProperty("URL_TOR_CHECK") ?: "https://check.torproject.org/api/ip"}\"")
        buildConfigField("String", "URL_IP_LOOKUP",
            "\"${project.findProperty("URL_IP_LOOKUP") ?: "http://ip-api.com/json"}\"")

        // DNS Check URLs (for network RTT measurement)
        buildConfigField("String", "URL_DNS_CHECK_CLOUDFLARE",
            "\"${project.findProperty("URL_DNS_CHECK_CLOUDFLARE") ?: "https://1.1.1.1"}\"")
        buildConfigField("String", "URL_DNS_CHECK_GOOGLE",
            "\"${project.findProperty("URL_DNS_CHECK_GOOGLE") ?: "https://dns.google"}\"")
        buildConfigField("String", "URL_DNS_CHECK_OPENDNS",
            "\"${project.findProperty("URL_DNS_CHECK_OPENDNS") ?: "https://208.67.222.222"}\"")

        // Data Source URLs
        buildConfigField("String", "URL_OUI_DATABASE",
            "\"${project.findProperty("URL_OUI_DATABASE") ?: "https://standards-oui.ieee.org/oui/oui.csv"}\"")
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

            // Sideload builds have all features enabled
            buildConfigField("boolean", "FEATURE_FLIPPER_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_ULTRASONIC_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_ANDROID_AUTO_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_NUKE_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_AI_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_TOR_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_MAP_ENABLED", "true")

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

            // System builds have all features enabled
            buildConfigField("boolean", "FEATURE_FLIPPER_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_ULTRASONIC_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_ANDROID_AUTO_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_NUKE_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_AI_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_TOR_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_MAP_ENABLED", "true")

            // System app label suffix for identification
            manifestPlaceholders["appLabel"] = "@string/app_name_system"
        }

        // OEM embedded version (signed with platform certificate)
        // OEM partners can customize the application ID via OEM_PACKAGE_NAME in gradle.properties
        create("oem") {
            dimension = "installMode"
            // Use OEM-specified package name if provided, otherwise default to com.flockyou
            applicationId = oemPackageName
            applicationIdSuffix = ""
            versionNameSuffix = "-oem"

            buildConfigField("boolean", "IS_SYSTEM_BUILD", "true")
            buildConfigField("boolean", "IS_OEM_BUILD", "true")
            buildConfigField("String", "BUILD_MODE", "\"oem\"")
            // Expose the configured package name for runtime checks
            buildConfigField("String", "OEM_PACKAGE_NAME", "\"$oemPackageName\"")

            // OEM builds use configurable feature flags (read from gradle.properties)
            // OEMs can override these in their local gradle.properties or via command line
            buildConfigField("boolean", "FEATURE_FLIPPER_ENABLED", "$oemFeatureFlipperEnabled")
            buildConfigField("boolean", "FEATURE_ULTRASONIC_ENABLED", "$oemFeatureUltrasonicEnabled")
            buildConfigField("boolean", "FEATURE_ANDROID_AUTO_ENABLED", "$oemFeatureAndroidAutoEnabled")
            buildConfigField("boolean", "FEATURE_NUKE_ENABLED", "$oemFeatureNukeEnabled")
            buildConfigField("boolean", "FEATURE_AI_ENABLED", "$oemFeatureAiEnabled")
            buildConfigField("boolean", "FEATURE_TOR_ENABLED", "$oemFeatureTorEnabled")
            buildConfigField("boolean", "FEATURE_MAP_ENABLED", "$oemFeatureMapEnabled")

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
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.2")
    
    // Compose
    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material") // For pull-to-refresh
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    
    // Hilt
    implementation("com.google.dagger:hilt-android:2.54")
    ksp("com.google.dagger:hilt-android-compiler:2.54")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")
    
    // Room with SQLCipher encryption
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("net.zetetic:sqlcipher-android:4.12.0@aar")
    implementation("androidx.sqlite:sqlite-ktx:2.6.2")
    
    // Location
    implementation("com.google.android.gms:play-services-location:21.1.0")
    
    // Maps - OpenStreetMap (no API key required)
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    
    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    
    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // WorkManager for periodic background tasks
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("androidx.hilt:hilt-work:1.3.0")
    ksp("androidx.hilt:hilt-compiler:1.3.0")

    // Guava for ListenableFuture (needed by WorkManager awaits)
    implementation("com.google.guava:guava:32.1.3-android")

    // OkHttp for HTTP downloads with TLS support
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-tls:4.12.0")

    // USB Serial for Flipper Zero USB CDC communication
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")

    // Security - Encrypted SharedPreferences for storing DB passphrase
    implementation("androidx.security:security-crypto:1.1.0")

    // Biometric authentication
    implementation("androidx.biometric:biometric:1.1.0")

    // Android Auto - Car App Library (optional)
    implementation("androidx.car.app:app:1.7.0")

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
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.01.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
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
 *
 * Set SKIP_FLIPPER_BUILD=true to skip Flipper-related tasks (e.g., in CI).
 */
val flipperAppDir = file("${rootDir}/flipper_app/flock_bridge")
val flipperBuildDir = file("${buildDir}/flipper")
val fapOutputName = "flock_bridge.fap"

// Check if Flipper build should be skipped (e.g., in CI without ufbt)
val skipFlipperBuild = System.getenv("SKIP_FLIPPER_BUILD")?.toBoolean() == true ||
    project.findProperty("skipFlipperBuild")?.toString()?.toBoolean() == true

// Check if ufbt is available (cached result)
val ufbtAvailable: Boolean by lazy {
    if (skipFlipperBuild) {
        println("Flipper build skipped via SKIP_FLIPPER_BUILD")
        false
    } else {
        try {
            val process = ProcessBuilder("ufbt", "--version")
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Check if ufbt (micro Flipper Build Tool) is available.
 */
tasks.register("checkUfbt") {
    group = "flipper"
    description = "Check if ufbt is installed"

    doLast {
        if (skipFlipperBuild) {
            println("Flipper build skipped via SKIP_FLIPPER_BUILD environment variable")
            return@doLast
        }
        if (!ufbtAvailable) {
            println("WARNING: ufbt not available. Flipper FAP will not be built.")
            println("Install with: pip install ufbt")
            return@doLast
        }
        println("ufbt found")
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

    onlyIf { ufbtAvailable }

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
        if (ufbtAvailable) {
            exec {
                workingDir = flipperAppDir
                commandLine("ufbt", "clean")
                isIgnoreExitValue = true
            }
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

    onlyIf { ufbtAvailable }

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

    onlyIf { ufbtAvailable }

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

    onlyIf { ufbtAvailable }

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

// Automatically bundle FAP for release builds (must run before any task that reads assets)
afterEvaluate {
    tasks.matching { task ->
        task.name.contains("Release") && (
            task.name.startsWith("merge") && task.name.endsWith("Assets") ||
            task.name.contains("Lint") ||
            task.name.contains("Assets") ||
            task.name.startsWith("process") && task.name.endsWith("Resources")
        )
    }.configureEach {
        dependsOn("bundleFlipperFap")
    }
}

// ================================================================
// OEM System Integration Tasks
// ================================================================

/**
 * Generate privapp-permissions XML file dynamically based on the configured application ID.
 * This is required for OEM partners who use custom package names.
 *
 * Run manually: ./gradlew generatePrivappPermissions
 * Runs automatically before OEM release builds.
 *
 * Output: build/generated/oem/privapp-permissions-<package>.xml
 */
tasks.register("generatePrivappPermissions") {
    group = "oem"
    description = "Generate privapp-permissions XML with the configured OEM package name"

    val systemDir = file("${rootDir}/system")
    val outputFile = file("${layout.buildDirectory.get()}/generated/oem/privapp-permissions-${oemPackageName.replace(".", "-")}.xml")

    inputs.property("packageName", oemPackageName)
    outputs.file(outputFile)

    doLast {
        // Create output directory
        outputFile.parentFile.mkdirs()

        val xmlContent = """<?xml version="1.0" encoding="utf-8"?>
<!--
    Privileged permission whitelist for ${if (oemPackageName != defaultPackageName) "OEM partner build" else "Flock You"}.
    Generated automatically by: ./gradlew generatePrivappPermissions

    Package: $oemPackageName

    This file should be placed in:
    /system/etc/permissions/privapp-permissions-flockyou.xml

    or for newer Android versions:
    /system_ext/etc/permissions/privapp-permissions-flockyou.xml

    The APK should be installed to:
    /system/priv-app/FlockYou/FlockYou.apk

    or for newer Android versions:
    /system_ext/priv-app/FlockYou/FlockYou.apk
-->
<permissions>
    <privapp-permissions package="$oemPackageName">
        <!-- Bluetooth privileged: Bypass BLE duty cycling for continuous scanning -->
        <permission name="android.permission.BLUETOOTH_PRIVILEGED"/>

        <!-- MAC address access: Get real hardware addresses instead of randomized ones -->
        <permission name="android.permission.PEERS_MAC_ADDRESS"/>
        <permission name="android.permission.LOCAL_MAC_ADDRESS"/>

        <!-- Privileged phone state: Access IMEI/IMSI for IMSI catcher detection -->
        <permission name="android.permission.READ_PRIVILEGED_PHONE_STATE"/>

        <!-- Internal connectivity: Control WiFi scan throttling -->
        <permission name="android.permission.CONNECTIVITY_INTERNAL"/>
        <permission name="android.permission.NETWORK_SETTINGS"/>

        <!-- Process management: Keep service running persistently -->
        <permission name="android.permission.PERSISTENT_ACTIVITY"/>
        <permission name="android.permission.START_ACTIVITIES_FROM_BACKGROUND"/>

        <!-- Multi-user support for shared devices -->
        <permission name="android.permission.INTERACT_ACROSS_USERS"/>
        <permission name="android.permission.MANAGE_USERS"/>

        <!-- Usage stats for battery optimization -->
        <permission name="android.permission.PACKAGE_USAGE_STATS"/>
    </privapp-permissions>
</permissions>
"""
        outputFile.writeText(xmlContent)

        println("Generated privapp-permissions XML:")
        println("  Package: $oemPackageName")
        println("  Output: ${outputFile.absolutePath}")

        // Also update the source file if using a custom package name
        if (oemPackageName != defaultPackageName) {
            println("")
            println("NOTE: Copy this file to your system integration:")
            println("  cp ${outputFile.absolutePath} /path/to/aosp/vendor/flockyou/privapp-permissions-flockyou.xml")
        }
    }
}

/**
 * Generate default-permissions XML file dynamically based on the configured application ID.
 * This pre-grants runtime permissions on first boot.
 *
 * Run manually: ./gradlew generateDefaultPermissions
 * Runs automatically before OEM release builds.
 */
tasks.register("generateDefaultPermissions") {
    group = "oem"
    description = "Generate default-permissions XML with the configured OEM package name"

    val outputFile = file("${layout.buildDirectory.get()}/generated/oem/default-permissions-${oemPackageName.replace(".", "-")}.xml")

    inputs.property("packageName", oemPackageName)
    outputs.file(outputFile)

    doLast {
        outputFile.parentFile.mkdirs()

        val xmlContent = """<?xml version="1.0" encoding="utf-8"?>
<!--
    Default runtime permissions for ${if (oemPackageName != defaultPackageName) "OEM partner build" else "Flock You"} on first boot.
    Generated automatically by: ./gradlew generateDefaultPermissions

    Package: $oemPackageName

    This file pre-grants runtime permissions so users don't need to
    manually grant them after installing. This is optional but recommended
    for OEM/system app deployments.

    Installation path:
    /system_ext/etc/default-permissions/default-permissions-flockyou.xml

    Note: This requires the ROM to support default permission grants.
    GrapheneOS and most AOSP-based ROMs support this.
-->
<exceptions>
    <exception package="$oemPackageName">
        <!-- Location permissions for WiFi/cellular scanning -->
        <permission name="android.permission.ACCESS_FINE_LOCATION" fixed="false"/>
        <permission name="android.permission.ACCESS_COARSE_LOCATION" fixed="false"/>
        <permission name="android.permission.ACCESS_BACKGROUND_LOCATION" fixed="false"/>

        <!-- Bluetooth permissions for BLE scanning -->
        <permission name="android.permission.BLUETOOTH_SCAN" fixed="false"/>
        <permission name="android.permission.BLUETOOTH_CONNECT" fixed="false"/>

        <!-- Phone state for cellular monitoring -->
        <permission name="android.permission.READ_PHONE_STATE" fixed="false"/>

        <!-- Notifications -->
        <permission name="android.permission.POST_NOTIFICATIONS" fixed="false"/>

        <!-- Nearby devices (Android 12+) -->
        <permission name="android.permission.NEARBY_WIFI_DEVICES" fixed="false"/>
    </exception>
</exceptions>
"""
        outputFile.writeText(xmlContent)

        println("Generated default-permissions XML:")
        println("  Package: $oemPackageName")
        println("  Output: ${outputFile.absolutePath}")
    }
}

/**
 * Generate all OEM system integration files.
 * This creates the necessary XML files and also generates a setup script.
 *
 * Run: ./gradlew generateOemSystemFiles
 */
tasks.register("generateOemSystemFiles") {
    group = "oem"
    description = "Generate all system integration files for OEM deployment"
    dependsOn("generatePrivappPermissions", "generateDefaultPermissions")

    val outputDir = file("${layout.buildDirectory.get()}/generated/oem")
    val scriptFile = file("${outputDir}/setup-oem-integration.sh")

    doLast {
        // Generate a setup script for OEM partners
        val scriptContent = """#!/bin/bash
# OEM System Integration Setup Script
# Generated by: ./gradlew generateOemSystemFiles
#
# Package Name: $oemPackageName
#
# This script copies the generated files to the appropriate locations
# for AOSP/ROM integration.

set -e

SCRIPT_DIR="${'$'}(cd "${'$'}(dirname "${'$'}{BASH_SOURCE[0]}")" && pwd)"
PACKAGE_NAME="$oemPackageName"

echo "=========================================="
echo "OEM System Integration for: ${'$'}PACKAGE_NAME"
echo "=========================================="
echo ""

# Check if AOSP root is provided
if [ -z "${'$'}1" ]; then
    echo "Usage: ${'$'}0 <aosp-root> [vendor-path]"
    echo ""
    echo "Arguments:"
    echo "  aosp-root    - Path to AOSP source tree"
    echo "  vendor-path  - Optional: vendor directory name (default: flockyou)"
    echo ""
    echo "Example:"
    echo "  ${'$'}0 /path/to/aosp flockyou"
    echo "  ${'$'}0 /path/to/grapheneos partner_security"
    exit 1
fi

AOSP_ROOT="${'$'}1"
VENDOR_PATH="${'$'}{2:-flockyou}"
TARGET_DIR="${'$'}AOSP_ROOT/vendor/${'$'}VENDOR_PATH"

echo "AOSP Root: ${'$'}AOSP_ROOT"
echo "Target: ${'$'}TARGET_DIR"
echo ""

# Create target directory
mkdir -p "${'$'}TARGET_DIR"

# Copy XML files
echo "Copying permission files..."
cp "${'$'}SCRIPT_DIR/privapp-permissions-${oemPackageName.replace(".", "-")}.xml" "${'$'}TARGET_DIR/privapp-permissions-flockyou.xml"
cp "${'$'}SCRIPT_DIR/default-permissions-${oemPackageName.replace(".", "-")}.xml" "${'$'}TARGET_DIR/default-permissions-flockyou.xml"

echo ""
echo "Files copied successfully!"
echo ""
echo "Next steps:"
echo "1. Copy your signed APK to: ${'$'}TARGET_DIR/FlockYou.apk"
echo "2. Update Android.bp/Android.mk in ${'$'}TARGET_DIR if needed"
echo "3. Add 'FlockYou' to PRODUCT_PACKAGES in your device.mk"
echo "4. Build your ROM"
"""
        scriptFile.writeText(scriptContent)
        scriptFile.setExecutable(true)

        println("")
        println("=== OEM System Files Generated ===")
        println("Output directory: ${outputDir.absolutePath}")
        println("")
        println("Files:")
        println("  - privapp-permissions-${oemPackageName.replace(".", "-")}.xml")
        println("  - default-permissions-${oemPackageName.replace(".", "-")}.xml")
        println("  - setup-oem-integration.sh")
        println("")
        println("Run the setup script:")
        println("  ${scriptFile.absolutePath} /path/to/aosp [vendor-name]")
    }
}

// Hook OEM file generation into OEM release builds
tasks.matching { it.name == "assembleOemRelease" }.configureEach {
    dependsOn("generateOemSystemFiles")
}
