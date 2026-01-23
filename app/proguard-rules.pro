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
-keepattributes Exceptions

# Keep Kotlin metadata for reflection
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keep class kotlin.Metadata { *; }

# ============================================================
# KOTLIN COROUTINES - CRITICAL FOR STATEFLOW/FLOW
# ============================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
# Keep StateFlow and SharedFlow internals
-keep class kotlinx.coroutines.flow.** { *; }
-keep class kotlinx.coroutines.channels.** { *; }

# ============================================================
# KOTLIN DATA CLASSES - PRESERVE FIELD NAMES FOR GSON
# ============================================================
# R8 can obfuscate field names which breaks Gson reflection
# Keep all field names in data classes used for serialization
-keepclassmembers class com.flockyou.**.* {
    <fields>;
}
# Keep all enum entries
-keepclassmembers enum com.flockyou.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    **[] $VALUES;
    public *;
}
# Keep sealed class subclasses
-keep class * extends com.flockyou.service.ScanningService$ScanStatus { *; }
-keep class * extends com.flockyou.service.ScanningService$SubsystemStatus { *; }

# ============================================================
# KOTLIN INTRINSICS - Prevent R8 from removing null checks
# ============================================================
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNull(...);
    static void checkNotNullParameter(...);
    static void checkParameterIsNotNull(...);
    static void checkNotNullExpressionValue(...);
    static void checkExpressionValueIsNotNull(...);
    static void checkReturnedValueIsNotNull(...);
    static void throwUninitializedPropertyAccessException(...);
}

# ============================================================
# NUCLEAR OPTION - DISABLE OBFUSCATION FOR ENTIRE APP
# ============================================================
# This ensures no class/method/field names are changed, preventing
# any serialization, reflection, or callback registration issues.
# Trade-off: Slightly larger APK, but guaranteed to work.
-keepnames class com.flockyou.** { *; }
-keepnames interface com.flockyou.** { *; }
-keepclassmembernames class com.flockyou.** { *; }

# Keep ALL anonymous inner classes in the app (callbacks, etc.)
-keepclassmembers class com.flockyou.** {
    *** $*;
}

# ============================================================
# BLE SCAN CALLBACKS - Anonymous implementations
# ============================================================
# Multiple classes create anonymous ScanCallback objects that
# R8 might strip or rename, breaking BLE scanning
-keepclassmembers class com.flockyou.service.ScanningService {
    private *** bleScanCallback;
    private *** wifiScanReceiver;
}
-keepclassmembers class com.flockyou.scanner.standard.StandardBluetoothScanner {
    private *** scanCallbackImpl;
}
-keepclassmembers class com.flockyou.scanner.system.SystemBluetoothScanner {
    private *** scanCallbackImpl;
}
-keepclassmembers class com.flockyou.scanner.flipper.FlipperBluetoothClient {
    private *** scanCallback;
    private *** gattCallback;
}
-keepclassmembers class com.flockyou.ui.screens.FlipperSettingsViewModel {
    private *** scanCallback;
}

# ============================================================
# GNSS/TELEPHONY CALLBACKS - Anonymous implementations
# ============================================================
-keepclassmembers class com.flockyou.monitoring.GnssSatelliteMonitor {
    private *** gnssStatusCallback;
    private *** measurementsCallback;
    private *** locationCallback;
}
-keepclassmembers class com.flockyou.monitoring.SatelliteMonitor {
    private *** telephonyCallback;
}
-keepclassmembers class com.flockyou.service.CellularMonitor {
    private *** telephonyCallback;
    private *** phoneStateListener;
}

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

# ============================================================
# FLIPPER ZERO INTEGRATION - COMPREHENSIVE
# ============================================================
-keep class com.flockyou.scanner.flipper.** { *; }
-keepclassmembers class com.flockyou.scanner.flipper.** { *; }

# Flipper Protocol - Binary parsing requires exact field names
-keep class com.flockyou.scanner.flipper.FlipperProtocol { *; }
-keep class com.flockyou.scanner.flipper.FlipperProtocol$* { *; }

# Flipper Models - Data classes for protocol communication
-keep class com.flockyou.scanner.flipper.FlipperWifiScanResult { *; }
-keep class com.flockyou.scanner.flipper.FlipperWifiNetwork { *; }
-keep class com.flockyou.scanner.flipper.FlipperSubGhzScanResult { *; }
-keep class com.flockyou.scanner.flipper.FlipperSubGhzDetection { *; }
-keep class com.flockyou.scanner.flipper.FlipperSubGhzScanStatus { *; }
-keep class com.flockyou.scanner.flipper.FlipperBleScanResult { *; }
-keep class com.flockyou.scanner.flipper.FlipperBleDevice { *; }
-keep class com.flockyou.scanner.flipper.FlipperIrScanResult { *; }
-keep class com.flockyou.scanner.flipper.FlipperIrDetection { *; }
-keep class com.flockyou.scanner.flipper.FlipperNfcScanResult { *; }
-keep class com.flockyou.scanner.flipper.FlipperNfcDetection { *; }
-keep class com.flockyou.scanner.flipper.FlipperStatusResponse { *; }
-keep class com.flockyou.scanner.flipper.FlipperWipsAlert { *; }

# Flipper Enums - Must preserve names for protocol parsing
-keepclassmembers enum com.flockyou.scanner.flipper.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    **[] $VALUES;
    public *;
}
-keep enum com.flockyou.scanner.flipper.WifiSecurityType { *; }
-keep enum com.flockyou.scanner.flipper.SubGhzModulation { *; }
-keep enum com.flockyou.scanner.flipper.BleDeviceType { *; }
-keep enum com.flockyou.scanner.flipper.IrProtocol { *; }
-keep enum com.flockyou.scanner.flipper.NfcType { *; }
-keep enum com.flockyou.scanner.flipper.FlipperConnectionState { *; }
-keep enum com.flockyou.scanner.flipper.FlipperConnectionPreference { *; }
-keep enum com.flockyou.scanner.flipper.FlipperHapticPattern { *; }
-keep enum com.flockyou.scanner.flipper.FlipperAlertSound { *; }

# Flipper Settings - DataStore serialization requires field names
-keep class com.flockyou.scanner.flipper.FlipperSettings { *; }
-keepclassmembers class com.flockyou.scanner.flipper.FlipperSettings { *; }
-keep class com.flockyou.scanner.flipper.FlipperSettingsRepository { *; }
-keep class com.flockyou.scanner.flipper.RecentFlipperDevice { *; }
-keep class com.flockyou.scanner.flipper.AutoReconnectState { *; }
-keep class com.flockyou.scanner.flipper.DiscoveredFlipperDevice { *; }

# Flipper BLE Client - GATT callbacks
-keepclassmembers class com.flockyou.scanner.flipper.FlipperBluetoothClient {
    private *** gattCallback;
    private *** scanCallback;
    private *** connectionStateCallback;
}

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
# ============================================================
# DETECTION FRAMEWORK - COMPREHENSIVE
# ============================================================
-keep class com.flockyou.detection.** { *; }
-keepclassmembers class com.flockyou.detection.** { *; }
-keep class com.flockyou.detection.handler.** { *; }
-keepclassmembers class com.flockyou.detection.handler.** { *; }
-keep class com.flockyou.detection.framework.** { *; }
-keepclassmembers class com.flockyou.detection.framework.** { *; }
-keep class com.flockyou.detection.config.** { *; }
-keepclassmembers class com.flockyou.detection.config.** { *; }
-keep class com.flockyou.detection.profile.** { *; }
-keepclassmembers class com.flockyou.detection.profile.** { *; }

# Detection Handler inner classes and callbacks
-keep class com.flockyou.detection.handler.*$* { *; }
-keep class com.flockyou.detection.framework.*$* { *; }
-keep class com.flockyou.detection.config.DetectionConfig { *; }
-keep class com.flockyou.detection.config.DetectionConfig$* { *; }

# Detection enums
-keepclassmembers enum com.flockyou.detection.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    **[] $VALUES;
    public *;
}

# ============================================================
# ============================================================
# AI/LLM CLASSES - COMPREHENSIVE
# ============================================================
# MediaPipe LLM inference requires all classes preserved
-keep class com.flockyou.ai.** { *; }
-keepclassmembers class com.flockyou.ai.** { *; }

# AI Correlation Analysis subpackage
-keep class com.flockyou.ai.correlation.** { *; }
-keepclassmembers class com.flockyou.ai.correlation.** { *; }
-keep class com.flockyou.ai.correlation.CorrelatedThreatAnalysis$* { *; }
-keep class com.flockyou.ai.correlation.CrossDomainAnalyzer$* { *; }

# AI sealed classes and their subclasses
-keep class com.flockyou.ai.ProgressiveAnalysisResult { *; }
-keep class com.flockyou.ai.ProgressiveAnalysisResult$* { *; }
-keep class com.flockyou.ai.AiAnalysisResult { *; }
-keep class com.flockyou.ai.AiAnalysisResult$* { *; }
-keep class com.flockyou.ai.LlmEngineManager$* { *; }
-keep class com.flockyou.ai.MediaPipeLlmClient$* { *; }
-keep class com.flockyou.ai.GeminiNanoClient$* { *; }
-keep class com.flockyou.ai.DetectionAnalyzer$* { *; }
-keep class com.flockyou.ai.RuleBasedAnalyzer$* { *; }
-keep class com.flockyou.ai.LlmOutputParser$* { *; }

# AI enums
-keepclassmembers enum com.flockyou.ai.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    **[] $VALUES;
    public *;
}

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
# ============================================================
# ANDROID AUTO - COMPREHENSIVE
# ============================================================
-keep class com.flockyou.auto.** { *; }
-keepclassmembers class com.flockyou.auto.** { *; }

# Car App Screens and Sessions
-keep class com.flockyou.auto.FlockYouCarAppService { *; }
-keep class com.flockyou.auto.FlockYouSession { *; }
-keep class com.flockyou.auto.*Screen { *; }
-keep class com.flockyou.auto.*Screen$* { *; }

# Android Auto data models
-keep class com.flockyou.auto.*State { *; }
-keep class com.flockyou.auto.*Data { *; }
-keep class com.flockyou.auto.*Model { *; }

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
-keep class com.flockyou.service.ScanningServiceConnection$* { *; }

# Keep ALL members of classes used in IPC serialization (Gson needs field names)
-keepclassmembers class com.flockyou.service.ScanningService$SeenDevice { *; }
-keepclassmembers class com.flockyou.service.ScanningService$ScanConfig { *; }
-keepclassmembers class com.flockyou.service.ScanningService$ScanStatistics { *; }
-keepclassmembers class com.flockyou.service.ScanningService$ScanError { *; }
-keepclassmembers class com.flockyou.service.ScanningService$DetectorHealthStatus { *; }
-keepclassmembers class com.flockyou.service.ScanningService$LearnedSignature { *; }

# Monitoring package data classes - CRITICAL for GNSS/Satellite/Cellular data
-keepclassmembers class com.flockyou.monitoring.GnssSatelliteMonitor$* { *; }
-keepclassmembers class com.flockyou.monitoring.SatelliteMonitor$* { *; }
-keepclassmembers class com.flockyou.monitoring.SatelliteDetectionHeuristics$* { *; }
-keepclassmembers class com.flockyou.service.CellularMonitor$* { *; }
-keepclassmembers class com.flockyou.service.RogueWifiMonitor$* { *; }
-keepclassmembers class com.flockyou.service.UltrasonicDetector$* { *; }
-keepclassmembers class com.flockyou.service.RfSignalAnalyzer$* { *; }

# Data model classes - used throughout IPC
-keepclassmembers class com.flockyou.data.model.** { *; }

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

# CRITICAL: Keep TypeToken subclasses for Gson reflection
# R8 aggressively strips anonymous TypeToken classes, breaking JSON deserialization
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keepattributes EnclosingMethod

# Keep anonymous TypeToken instances used in IPC serialization
# These are created inline in ScanningServiceIpc and ScanningServiceConnection
-keepclassmembers class com.flockyou.service.ScanningServiceIpc$* { *; }
-keepclassmembers class com.flockyou.service.ScanningServiceConnection$* { *; }

# ============================================================
# ANDROID SYSTEM CALLBACKS - CRITICAL FOR RELEASE BUILDS
# ============================================================
# These callbacks are registered via reflection by Android system
# R8 may strip them if it thinks they're unused

# GNSS/GPS Callbacks (for satellite monitoring)
-keep class * extends android.location.GnssStatus$Callback { *; }
-keep class * extends android.location.GnssMeasurementsEvent$Callback { *; }
-keep class * extends android.location.GnssNavigationMessage$Callback { *; }
-keep class * implements android.location.LocationListener { *; }
-keep class android.location.GnssStatus { *; }
-keep class android.location.GnssMeasurement { *; }
-keep class android.location.GnssClock { *; }

# Telephony Callbacks (for cellular monitoring - IMSI catcher detection)
-keep class * extends android.telephony.PhoneStateListener { *; }
-keep class * extends android.telephony.TelephonyCallback { *; }
-keep class * extends android.telephony.TelephonyCallback$* { *; }
-keep class * implements android.telephony.TelephonyCallback$* { *; }

# Telephony data classes (for NTN/Satellite modem monitoring)
-keep class android.telephony.CellInfo { *; }
-keep class android.telephony.CellInfo$* { *; }
-keep class android.telephony.CellInfoNr { *; }
-keep class android.telephony.CellIdentityNr { *; }
-keep class android.telephony.CellSignalStrengthNr { *; }
-keep class android.telephony.ServiceState { *; }
-keep class android.telephony.NetworkRegistrationInfo { *; }
-keep class android.telephony.TelephonyDisplayInfo { *; }
-keep class android.telephony.SignalStrength { *; }

# Android 14+ Satellite Manager API (for NTN satellite modem)
-keep class android.telephony.satellite.** { *; }
-keep class * implements android.telephony.satellite.SatelliteCallback { *; }
-dontwarn android.telephony.satellite.**

# BLE Scan Callbacks (for device detection)
-keep class * extends android.bluetooth.le.ScanCallback { *; }
-keep class android.bluetooth.le.ScanResult { *; }
-keep class android.bluetooth.le.ScanRecord { *; }

# WiFi Scan Callbacks
-keep class * implements android.content.BroadcastReceiver { *; }
-keep class android.net.wifi.ScanResult { *; }

# Audio Recording (for ultrasonic beacon detection)
-keep class android.media.AudioRecord { *; }
-keep class android.media.AudioFormat { *; }
-keep class android.media.AudioFormat$Builder { *; }

# Keep lambda implementations used as callbacks
-keepclassmembers class com.flockyou.** {
    private static synthetic void lambda$*(...);
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
# GOOGLE TINK / CRYPTO (ML Kit dependencies)
# ============================================================
# These are optional dependencies used by KeysDownloader that we don't use
-dontwarn com.google.api.client.http.**
-dontwarn com.google.api.client.http.javanet.**
-dontwarn com.google.crypto.tink.util.KeysDownloader
-dontwarn org.joda.time.**

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

# ============================================================
# R8 OPTIMIZATION OVERRIDES - PREVENT AGGRESSIVE OPTIMIZATIONS
# ============================================================
# Prevent R8 from removing "unused" code that's actually used via reflection
-keepclassmembers,allowshrinking class com.flockyou.** {
    <methods>;
}

# Prevent R8 from inlining methods that are called via reflection
-keepclassmembers class com.flockyou.** {
    public <methods>;
    protected <methods>;
}

# Keep constructors for dependency injection
-keepclassmembers class com.flockyou.** {
    public <init>(...);
    @javax.inject.Inject <init>(...);
}

# Prevent R8 from removing companion objects
-keepclassmembers class com.flockyou.** {
    public static ** Companion;
    ** INSTANCE;
}

# Keep object singletons
-keepclassmembers class com.flockyou.**$Companion {
    *;
}

# ============================================================
# FINAL CATCH-ALL - Keep everything with @Keep annotation
# ============================================================
-keep @androidx.annotation.Keep class * { *; }
-keep class * {
    @androidx.annotation.Keep *;
}
