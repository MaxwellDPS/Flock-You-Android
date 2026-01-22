# ============================================================
# Flock-You ProGuard Rules - COMPREHENSIVE
# ============================================================
# This file ensures all app classes are protected from obfuscation
# that would break functionality (IPC, reflection, serialization)

# ============================================================
# GENERAL KOTLIN RULES
# ============================================================
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes InnerClasses,EnclosingMethod

# Keep Kotlin metadata for reflection
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keep class kotlin.Metadata { *; }

# ============================================================
# APPLICATION CORE CLASSES
# ============================================================
-keep class com.flockyou.FlockYouApplication { *; }
-keep class com.flockyou.MainActivity { *; }

# ============================================================
# ALL SERVICES (Manifest-declared, must keep names)
# ============================================================
-keep class com.flockyou.service.ScanningService { *; }
-keep class com.flockyou.service.ScanningService$* { *; }
-keep class com.flockyou.service.ServiceRestartJobService { *; }
-keep class com.flockyou.service.QuickWipeTileService { *; }
-keep class com.flockyou.auto.FlockYouCarAppService { *; }

# ============================================================
# ALL BROADCAST RECEIVERS (Manifest-declared)
# ============================================================
-keep class com.flockyou.service.BootReceiver { *; }
-keep class com.flockyou.service.ServiceRestartReceiver { *; }
-keep class com.flockyou.service.ScreenLockReceiver { *; }
-keep class com.flockyou.service.QuickWipeReceiver { *; }
-keep class com.flockyou.service.nuke.BootWatcher { *; }
-keep class com.flockyou.service.nuke.UsbWatchdogReceiver { *; }
-keep class com.flockyou.service.nuke.SimStateReceiver { *; }
-keep class com.flockyou.service.nuke.NetworkIsolationReceiver { *; }
-keep class com.flockyou.scanner.flipper.FlipperAlertBroadcastReceiver { *; }

# ============================================================
# ALL ACTIVITIES
# ============================================================
-keep class com.flockyou.ui.EmergencyAlertActivity { *; }
-keep class com.flockyou.service.QuickWipeConfirmationActivity { *; }
-keep class com.flockyou.debug.ScreenshotHelperActivity { *; }

# ============================================================
# SERVICE MONITORING CLASSES - CRITICAL
# ============================================================
# These classes handle sensor data collection - if obfuscated,
# no BT/WiFi/GNSS/Cell data will appear in the UI
-keep class com.flockyou.service.CellularMonitor { *; }
-keep class com.flockyou.service.CellularMonitor$* { *; }
-keep class com.flockyou.service.RogueWifiMonitor { *; }
-keep class com.flockyou.service.RogueWifiMonitor$* { *; }
-keep class com.flockyou.service.RfSignalAnalyzer { *; }
-keep class com.flockyou.service.RfSignalAnalyzer$* { *; }
-keep class com.flockyou.service.UltrasonicDetector { *; }
-keep class com.flockyou.service.UltrasonicDetector$* { *; }

# ============================================================
# MONITORING PACKAGE - GNSS/Satellite detection
# ============================================================
-keep class com.flockyou.monitoring.** { *; }

# ============================================================
# SCANNER PACKAGE - All scanner implementations
# ============================================================
# Scanner interfaces and base classes
-keep class com.flockyou.scanner.ScannerInterfaces { *; }
-keep class com.flockyou.scanner.ScannerInterfaces$* { *; }
-keep class com.flockyou.scanner.ScannerFactory { *; }
-keep class com.flockyou.scanner.ScannerFactory$* { *; }
-keep class com.flockyou.scanner.ScannerModeHelper { *; }

# Standard API scanners
-keep class com.flockyou.scanner.standard.** { *; }

# System-level scanners (privileged)
-keep class com.flockyou.scanner.system.** { *; }

# Flipper Zero integration
-keep class com.flockyou.scanner.flipper.** { *; }

# Active probes
-keep class com.flockyou.scanner.probes.** { *; }

# ============================================================
# DATA MODELS - All serialized via Gson/Room
# ============================================================
-keep class com.flockyou.data.** { *; }
-keepclassmembers class com.flockyou.data.** { *; }

# Ensure enum valueOf works
-keepclassmembers enum com.flockyou.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============================================================
# DETECTION FRAMEWORK
# ============================================================
-keep class com.flockyou.detection.** { *; }
-keep class com.flockyou.detection.handler.** { *; }
-keep class com.flockyou.detection.framework.** { *; }
-keep class com.flockyou.detection.config.** { *; }
-keep class com.flockyou.detection.profile.** { *; }

# ============================================================
# AI/LLM CLASSES
# ============================================================
-keep class com.flockyou.ai.** { *; }

# ============================================================
# SECURITY/NUKE CLASSES
# ============================================================
-keep class com.flockyou.security.** { *; }
-keep class com.flockyou.service.nuke.** { *; }

# ============================================================
# NETWORK CLASSES (Tor support)
# ============================================================
-keep class com.flockyou.network.** { *; }

# ============================================================
# PRIVILEGE/SYSTEM INTEGRATION
# ============================================================
-keep class com.flockyou.privilege.** { *; }

# ============================================================
# ANDROID AUTO
# ============================================================
-keep class com.flockyou.auto.** { *; }

# ============================================================
# TEST MODE (Debug/Testing support)
# ============================================================
-keep class com.flockyou.testmode.** { *; }

# ============================================================
# CONFIG CLASSES
# ============================================================
-keep class com.flockyou.config.** { *; }

# ============================================================
# DOMAIN/USE CASES
# ============================================================
-keep class com.flockyou.domain.** { *; }

# ============================================================
# UTILITY CLASSES
# ============================================================
-keep class com.flockyou.util.** { *; }

# ============================================================
# UI VIEWMODELS - Hilt injection requires names
# ============================================================
-keep class com.flockyou.ui.screens.**ViewModel { *; }
-keep class com.flockyou.ui.screens.**UiState { *; }
-keep class com.flockyou.ui.components.**ViewModel { *; }

# ============================================================
# WORKER CLASSES (WorkManager)
# ============================================================
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}
-keep class com.flockyou.worker.** { *; }

# ============================================================
# IPC/MESSENGER - Critical for inter-process communication
# ============================================================
-keep class com.flockyou.service.ScanningServiceIpc { *; }
-keep class com.flockyou.service.ScanningServiceIpc$* { *; }
-keep class com.flockyou.service.ScanningServiceConnection { *; }

# ============================================================
# CALLBACK INTERFACES
# ============================================================
-keep interface com.flockyou.** { *; }
-keepclassmembers class * implements com.flockyou.service.ScanningService$DetectorCallback { *; }

# ============================================================
# SEALED CLASSES - Subclasses must be kept for type checking
# ============================================================
-keep class com.flockyou.privilege.PrivilegeMode { *; }
-keep class com.flockyou.privilege.PrivilegeMode$* { *; }
-keep class com.flockyou.ai.MediaPipeLlmStatus { *; }
-keep class com.flockyou.ai.MediaPipeLlmStatus$* { *; }
-keep class com.flockyou.ai.GeminiNanoStatus { *; }
-keep class com.flockyou.ai.GeminiNanoStatus$* { *; }
-keep class com.flockyou.ai.LlmEngineManager$EngineStatus { *; }
-keep class com.flockyou.ai.LlmEngineManager$EngineStatus$* { *; }
-keep class com.flockyou.data.ProtectionPreset { *; }
-keep class com.flockyou.data.ProtectionPreset$* { *; }
-keep class com.flockyou.scanner.flipper.FlipperMessage { *; }
-keep class com.flockyou.scanner.flipper.FlipperMessage$* { *; }
-keep class com.flockyou.testmode.TestScenario { *; }
-keep class com.flockyou.testmode.TestScenario$* { *; }
-keep class com.flockyou.security.DuressAuthenticator$DuressCheckResult { *; }
-keep class com.flockyou.security.DuressAuthenticator$DuressCheckResult$* { *; }
-keep class com.flockyou.scanner.probes.ActiveProbeSettings$ProbeAllowedResult { *; }
-keep class com.flockyou.scanner.probes.ActiveProbeSettings$ProbeAllowedResult$* { *; }
-keep class com.flockyou.ui.screens.ActiveProbesViewModel$ProbeExecutionState { *; }
-keep class com.flockyou.ui.screens.ActiveProbesViewModel$ProbeExecutionState$* { *; }

# ============================================================
# HILT DEPENDENCY INJECTION
# ============================================================
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.* <methods>;
}
-keepclasseswithmembers class * {
    @dagger.hilt.* <fields>;
}
-keepclasseswithmembers class * {
    @javax.inject.* <methods>;
}
-keepclasseswithmembers class * {
    @javax.inject.* <fields>;
}
-keep class com.flockyou.di.** { *; }

# ============================================================
# ROOM DATABASE
# ============================================================
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface *
-keepclassmembers @androidx.room.Dao interface * { *; }
-keep class com.flockyou.data.repository.Converters { *; }
-keep class com.flockyou.data.repository.FlockYouDatabase { *; }

# ============================================================
# GSON SERIALIZATION
# ============================================================
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ============================================================
# OKHTTP
# ============================================================
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ============================================================
# SQLCIPHER
# ============================================================
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-dontwarn net.sqlcipher.**

# ============================================================
# PROTOBUF (MediaPipe, ML Kit)
# ============================================================
-dontwarn com.google.protobuf.Internal$ProtoMethodMayReturnNull
-dontwarn com.google.protobuf.Internal$ProtoNonnullApi
-dontwarn com.google.protobuf.ProtoField
-dontwarn com.google.protobuf.ProtoPresenceBits
-dontwarn com.google.protobuf.ProtoPresenceCheckedField
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# ============================================================
# TENSORFLOW LITE
# ============================================================
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }

# ============================================================
# MEDIAPIPE
# ============================================================
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.tasks.genai.llminference.jni.proto.** { *; }
-dontwarn com.google.mediapipe.framework.image.**

# ============================================================
# ML KIT
# ============================================================
-keep class com.google.mlkit.genai.** { *; }
-keep class com.google.android.libraries.mlkit.** { *; }

# ============================================================
# GOOGLE MAPS / OSM
# ============================================================
-keep class com.google.android.gms.maps.** { *; }
-keep interface com.google.android.gms.maps.** { *; }
-keep class org.osmdroid.** { *; }

# ============================================================
# COROUTINES
# ============================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.flow.** { *; }

# ============================================================
# ANNOTATION PROCESSING (compile-time only)
# ============================================================
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**

# ============================================================
# ANDROID AUTO / CAR APP LIBRARY
# ============================================================
-keep class androidx.car.app.** { *; }
-keep class * extends androidx.car.app.Screen { *; }
-keep class * extends androidx.car.app.Session { *; }
-keep class * extends androidx.car.app.CarAppService { *; }

# ============================================================
# USB SERIAL (Flipper Zero)
# ============================================================
-keep class com.hoho.android.usbserial.** { *; }

# ============================================================
# BIOMETRIC
# ============================================================
-keep class androidx.biometric.** { *; }

# ============================================================
# DATASTORE
# ============================================================
-keep class androidx.datastore.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
