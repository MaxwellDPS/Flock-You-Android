plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    
    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-android-compiler:2.50")
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

    // Security - Encrypted SharedPreferences for storing DB passphrase
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Biometric authentication
    implementation("androidx.biometric:biometric:1.1.0")

    // Android Auto - Car App Library (optional)
    implementation("androidx.car.app:app:1.4.0")

    // Google AI - Gemini Nano on-device inference
    implementation("com.google.ai.edge.litert:litert:1.0.1")
    implementation("com.google.ai.edge.litert:litert-gpu:1.0.1")
    implementation("com.google.ai.edge.litert:litert-support:1.0.1")

    // Google Generative AI SDK (for Gemini API fallback when on-device unavailable)
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")
    
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
