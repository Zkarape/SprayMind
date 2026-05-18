# ─── LiteRT-LM JNI bridge ────────────────────────────────────────────────────
# All classes in the LiteRT namespace are accessed via JNI from native code.
# R8 cannot see those call-sites, so it would strip them without these rules.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.**

# ─── Kotlin coroutines ───────────────────────────────────────────────────────
# LiteRT's async streaming (Flow) depends on the coroutines runtime dispatcher
# factories being resolvable at runtime via ServiceLoader.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ─── CropGuard model layer ───────────────────────────────────────────────────
# Data classes are used by reference in Kotlin so R8 would normally keep them,
# but being explicit protects against aggressive full-mode shrinking.
-keep class com.cropguard.DetectionResult { *; }
-keep enum com.cropguard.Severity { *; }

# ─── WorkManager ─────────────────────────────────────────────────────────────
# Worker subclasses are instantiated by reflection via WorkerFactory.
-keep class com.cropguard.InspectionReminderWorker { *; }
-keep class androidx.work.** { *; }
-keepclassmembers class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepclassmembers class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ─── org.json ────────────────────────────────────────────────────────────────
# Used by SessionAdvisor + WeatherClient for tool-call JSON parsing.
-keep class org.json.** { *; }

# ─── Android OpenCL / GPU vendor libs ────────────────────────────────────────
# These are optional native libraries declared in the manifest. Suppressing the
# dontwarn avoids spurious "library class depends on program class" noise.
-dontwarn android.hardware.**
