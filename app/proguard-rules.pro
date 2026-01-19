# Add project specific ProGuard rules here.

# Keep data classes
-keepclassmembers class com.flockyou.data.model.** {
    *;
}

# Keep Room entities
-keep class com.flockyou.data.model.Detection { *; }
-keep class com.flockyou.data.model.OuiEntry { *; }

# Hilt
-keepclasseswithmembers class * {
    @dagger.hilt.* <methods>;
}
-keepclasseswithmembers class * {
    @dagger.hilt.* <fields>;
}

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Google Maps
-keep class com.google.android.gms.maps.** { *; }
-keep interface com.google.android.gms.maps.** { *; }

# OkHttp
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# WorkManager
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}
-keep class com.flockyou.worker.** { *; }

# SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-dontwarn net.sqlcipher.**

# Pattern data classes for JSON serialization
-keep class com.flockyou.data.model.SurveillancePattern { *; }
-keep class com.flockyou.data.patterns.PatternUpdateService$PatternExport { *; }

# IPC data classes - keep all fields for Gson serialization across processes
-keep class com.flockyou.service.ScanningService$SeenDevice { *; }
-keep class com.flockyou.service.ScanningService$LearnedSignature { *; }
-keep class com.flockyou.service.ScanningService$ScanStatistics { *; }
-keep class com.flockyou.service.ScanningService$ScanConfig { *; }
-keep class com.flockyou.service.ScanningService$ScanError { *; }
-keep class com.flockyou.service.CellularMonitor$* { *; }
-keep class com.flockyou.service.RogueWifiMonitor$* { *; }
-keep class com.flockyou.service.RfSignalAnalyzer$* { *; }
-keep class com.flockyou.service.UltrasonicDetector$* { *; }
-keep class com.flockyou.monitoring.SatelliteMonitor$* { *; }
-keep class com.flockyou.monitoring.GnssSatelliteMonitor$* { *; }

# Enum classes - ensure valueOf works correctly
-keepclassmembers enum com.flockyou.data.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
