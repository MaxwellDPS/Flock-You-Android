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

# ============================================================
# Protobuf - Used by MediaPipe and ML Kit GenAI
# ============================================================
# These are annotation classes that don't exist at runtime
-dontwarn com.google.protobuf.Internal$ProtoMethodMayReturnNull
-dontwarn com.google.protobuf.Internal$ProtoNonnullApi
-dontwarn com.google.protobuf.ProtoField
-dontwarn com.google.protobuf.ProtoPresenceBits
-dontwarn com.google.protobuf.ProtoPresenceCheckedField

# Keep protobuf classes used by MediaPipe
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# ============================================================
# javax.lang.model - Annotation processing classes (compile-time only)
# ============================================================
# These classes are only used during compilation, not at runtime
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.SimpleTypeVisitor8

# ============================================================
# TensorFlow Lite GPU Delegate
# ============================================================
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend

# Keep TensorFlow Lite classes
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }

# ============================================================
# MediaPipe GenAI / LLM Inference
# ============================================================
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }

# Keep proto classes for MediaPipe
-keep class com.google.mediapipe.tasks.genai.llminference.jni.proto.** { *; }

# MediaPipe framework image classes (optional dependency for image features)
-dontwarn com.google.mediapipe.framework.image.BitmapExtractor
-dontwarn com.google.mediapipe.framework.image.ByteBufferExtractor
-dontwarn com.google.mediapipe.framework.image.MPImage
-dontwarn com.google.mediapipe.framework.image.MPImageProperties
-dontwarn com.google.mediapipe.framework.image.MediaImageExtractor
-dontwarn com.google.mediapipe.framework.image.**

# ============================================================
# ML Kit GenAI Prompt (Gemini Nano)
# ============================================================
-keep class com.google.mlkit.genai.** { *; }
-keep class com.google.android.libraries.mlkit.** { *; }

# ============================================================
# AutoValue (shaded in some dependencies)
# ============================================================
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**

# ============================================================
# Guava
# ============================================================
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**

# ============================================================
# Coroutines
# ============================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
