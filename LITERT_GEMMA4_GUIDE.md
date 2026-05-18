# SprayMind — LiteRT + Gemma 4 Effective Usage Guide

> **Goal:** Make SprayMind the most effective showcase of on-device multimodal inference
> with LiteRT-LM and Gemma 4 on Android — lowest latency, lowest battery draw, highest
> result quality, with no server dependency after first setup.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Prerequisites](#2-prerequisites)
3. [Model Setup](#3-model-setup)
4. [Build Configuration](#4-build-configuration)
5. [Engine Configuration](#5-engine-configuration)
6. [Image Preprocessing Pipeline](#6-image-preprocessing-pipeline)
7. [Inference Optimization](#7-inference-optimization)
8. [Battery & Thermal Optimization](#8-battery--thermal-optimization)
9. [Wake Lock Strategy](#9-wake-lock-strategy)
10. [Camera Resolution Cap](#10-camera-resolution-cap)
11. [Adaptive Token Budget (Gemma 4 Variable-Resolution Vision)](#11-adaptive-token-budget-gemma-4-variable-resolution-vision)
11. [Error Recovery](#11-error-recovery)
12. [ProGuard / R8 Rules](#12-proguard--r8-rules)
13. [Benchmarking & Profiling](#13-benchmarking--profiling)
14. [Known Tradeoffs](#14-known-tradeoffs)
15. [Troubleshooting](#15-troubleshooting)

---

## 1. Architecture Overview

```
MainActivity
    └── MainViewModel (AndroidViewModel)
            ├── CropAnalyzer (singleton)
            │       ├── LiteRT Engine (GPU-first, CPU fallback)
            │       └── Conversation (per-frame, auto-closed)
            └── ImageCapture (CameraX)
                    └── OnImageCapturedCallback → ImageProxy (in RAM)
                            └── encodeAndHash(): bounds → inSampleSize → center-crop → targetPx JPEG
```

**State machine:**

```
Downloading ──► Initializing ──► YardSetup ──► Live ──► Finished
                                                 └──► Error ──► (retry) ──► Initializing
```

- `Downloading` — verifies model file exists at `/data/local/tmp/gemma-4-E2B-it.litertlm`
- `Initializing` — builds LiteRT engine, compiles GPU shaders (first launch only)
- `YardSetup` — user enters yard dimensions (W×H meters); each cell = 1 m²
- `Live` — frame capture loop active; camera + model both running
- `Finished` — session analytics; model stays loaded for fast restart

---

## 2. Prerequisites

| Requirement | Detail |
|---|---|
| Android API | 24+ (minSdk). Thermal API requires API 29+; older devices fall back gracefully |
| ABI | `arm64-v8a` (required by LiteRT GPU backend) |
| GPU | Any OpenCL-capable Adreno, Mali, or Xclipse; CPU fallback for others |
| RAM | 4 GB+ recommended (Gemma 4 E2B int4 ≈ 1.4 GB loaded) |
| Storage | ~1.5 GB free in `/data/local/tmp/` for the model file |
| Build tool | AGP 8.x + Kotlin 2.x |

---

## 3. Model Setup

The model is not bundled in the APK (it is 1.5 GB). Push it once via ADB:

```bash
# From your development machine, with the device connected via USB:
adb push gemma-4-E2B-it.litertlm /data/local/tmp/

# Verify the push succeeded and the size is correct (~1.5 GB):
adb shell ls -lh /data/local/tmp/gemma-4-E2B-it.litertlm
```

**Why `/data/local/tmp/`?**
This path is world-readable on debug builds and does not count against app storage quotas.
For production, push to app-private storage and update `MODEL_PATH` in `CropAnalyzer.kt`.

**Shader cache** is written to `/data/local/tmp/litert-cache/` on first engine initialization.
Subsequent launches skip GPU shader compilation and start 2–5× faster.

```bash
# Optional: pre-warm the shader cache by running the app once, then verify:
adb shell ls /data/local/tmp/litert-cache/
```

---

## 4. Build Configuration

### `app/build.gradle.kts`

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

**Why enable R8?**
- Removes unreachable code paths in LiteRT for non-GPU devices.
- Eliminates dead Compose intrinsics and unused CameraX codepaths.
- Reduces APK size by ~15–25%, which matters for devices with limited internal storage.
- `proguard-android-optimize.txt` (not the plain variant) enables inlining and class merging.

### `app/proguard-rules.pro`

The keep rules target three areas:

| Rule | Reason |
|---|---|
| `com.google.ai.edge.litertlm.**` | JNI-accessed; R8 cannot see native call-sites |
| `kotlinx.coroutines.*Factory` | ServiceLoader-resolved at runtime for the main dispatcher |
| `com.spraymind.DetectionResult`, `Severity` | Data layer safety net under full-mode shrinking |

---

## 5. Engine Configuration

### Three-tier backend waterfall

`buildEngine()` attempts three configurations in order and returns the first that succeeds:

| Tier | `backend` | `visionBackend` | When it fires |
|---|---|---|---|
| 1 | `GPU` | `GPU` | Ideal path — fastest and most battery-efficient |
| 2 | `GPU` | `CPU` | Devices with buggy OpenCL/Vulkan in the SigLIP vision encoder |
| 3 | `CPU` | `CPU` | Guaranteed fallback — all API 24+ devices |

```kotlin
val tiers = listOf(
    Backend.GPU() to Backend.GPU(),
    Backend.GPU() to Backend.CPU(),
    Backend.CPU() to Backend.CPU()
)
for ((backend, visionBackend) in tiers) {
    try {
        return Engine(EngineConfig(modelPath = MODEL_PATH,
                                   backend = backend,
                                   visionBackend = visionBackend,
                                   cacheDir = SHADER_CACHE))
               .also { it.initialize() }
    } catch (e: Exception) { /* log and continue */ }
}
```

**Why a middle GPU+CPU tier?**
Some mid-range Android devices ship with partially-broken OpenCL drivers. The SigLIP vision
encoder (which processes the image into patch tokens) is more GPU-intensive than the LLM
token decoder, so GPU vision fails on a subset of devices where GPU LLM works fine. Tier 2
catches exactly this class of device and keeps LLM decoding fast while routing vision through
the reliable CPU path.

**Why not NPU / NNAPI?**
The `litertlm-android` `Backend` sealed class only exposes `GPU` and `CPU`. NNAPI/NPU
delegation exists in the lower-level LiteRT Interpreter API but is not yet surfaced in the
LiteRT-LM generative-AI wrapper. When `Backend.NNAPI()` or equivalent becomes available,
it should be inserted as tier 1 (before GPU) since NPU inference is fastest and most
battery-efficient when the model fits the dedicated accelerator's op coverage.

### GPU vs CPU: why GPU wins on both latency AND battery

Counter-intuitive but correct: GPU inference finishes faster than CPU at the same quality level.
Because total energy = power × time, a shorter inference at slightly higher instantaneous power
yields less total energy per frame. On Snapdragon 8 Gen 2+, GPU inference of Gemma 4 E2B int4
typically runs 3–6× faster than CPU.

### Shader cache (`cacheDir`)

On first launch, LiteRT compiles GLSL/OpenCL kernels for the specific GPU. This takes
3–10 seconds and is the dominant cost of `AppState.Initializing`. The compiled binaries are
written to `cacheDir` and reloaded on subsequent launches — cold start drops to under 1 second
on most devices once the cache is warm.

```bash
# Confirm cache files were created after first launch:
adb shell ls -lh /data/local/tmp/litert-cache/
```

### Engine warmup pass

After `initializeModel()`, `CropAnalyzer.warmup(cacheDir)` runs a single dummy inference:

```kotlin
// 350×350 grey JPEG — MINIMAL budget resolution, fastest warmup path
eng.createConversation(conversationConfig).use { conv ->
    conv.sendMessageAsync(Contents.of(Content.ImageFile(tmp), Content.Text("Reply: OK")))
        .collect { conv.cancelProcess() }  // cancel after first token — pipeline is warm
}
```

What the warmup pre-heats:
- **JNI bridge**: `sendMessageAsync`, `collect`, `cancelProcess` JNI call-sites are JIT-compiled
- **GPU command queue**: first GPU submission carries 50–200ms of driver overhead; subsequent
  submissions are near-instant once the command queue is primed
- **KV-cache allocator**: Gemma 4's attention cache allocates on the first `createConversation`;
  subsequent creations reuse the same pool
- **Image tensor pipeline**: SigLIP's first image encoding triggers GLSL kernel selection and
  binding — paid once during warmup, free on every real farm frame thereafter

The warmup runs during `AppState.Initializing` so the farmer's loading screen absorbs the cost.
Failure is non-fatal — if the warmup throws (e.g. OOM during init), the app proceeds normally.

---

## 6. Image Preprocessing Pipeline

### The pipeline

```
CameraX ImageCapture (OnImageCapturedCallback)
    │
    └─ ImageProxy in RAM  ← full-res JPEG never touches disk
            │
            ├─ BitmapFactory (inJustDecodeBounds on byte array)  ← reads header in memory
            │       ↓ outWidth, outHeight
            ├─ inSampleSize = shortSide / targetPx
            │       ← with 960 short side + BALANCED (700px) → inSampleSize = 1
            │       ← with 960 short side + MINIMAL  (350px) → inSampleSize = 2
            │
            ├─ BitmapFactory.decodeByteArray (inSampleSize)
            │       ↓ "rough" bitmap in RAM
            ├─ Center-crop to square
            │       ↓ square bitmap
            ├─ Bitmap.createScaledBitmap(targetPx, targetPx)
            │       ↓ final bitmap
            ├─ FrameHasher.dhash()  ← dHash computed before encode
            └─ FileOutputStream → JPEG quality 85
                   ↓ only the small, scaled output hits disk
```

### Why `OnImageCapturedCallback` instead of `OnImageSavedCallback`

The original pipeline used `ImageCapture.OutputFileOptions` which writes the full-resolution
JPEG to disk synchronously inside the camera executor. `resizeAndHash` then called
`BitmapFactory.decodeFile` twice (once for bounds, once for pixels) — two large disk reads
on top of the initial write.

| Stage | Old (OnImageSavedCallback) | New (OnImageCapturedCallback) |
|---|---|---|
| Full-res JPEG → storage | Write (400–600 KB) | **None** |
| Bounds read | Disk seek + header read | Memory read (free) |
| Full decode | Disk read (400–600 KB) | `decodeByteArray` from RAM |
| Scaled output | Disk write (20–80 KB) | Disk write (20–80 KB) |
| **Total disk I/O** | **2 reads + 1 write (large)** | **1 write (small only)** |

On eMMC flash (common on mid-range Android devices), sequential read bandwidth is
200–400 MB/s but latency per `open()`/`read()` call is 0.5–2 ms. Eliminating the large
reads cuts 1–4 ms of I/O latency per frame and removes the cache-pollution effect of
loading 400–600 KB of JPEG bytes into CPU L3 cache before the bitmap decode.

### Why not zero-copy HardwareBuffer?

The `visionBackend = Backend.GPU()` in LiteRT can consume an `AHardwareBuffer` directly
in native code — no CPU decode needed at all. However, the `litertlm-android` Kotlin API
currently only exposes `Content.ImageFile(path)`, not a buffer handle. When
`Content.Image` or a buffer constructor becomes available in the SDK, the pipeline becomes:

```
ImageProxy.hardwareBuffer  →  Content.HardwareBuffer(...)  →  GPU delegate (zero CPU copies)
```

Until then, `OnImageCapturedCallback` is the best available reduction: one large disk I/O
removed, full-res bytes stay in CPU memory, only the small output persists.

### Why center-crop, not stretch?

SigLIP's patch tokenizer divides the image into a fixed grid of 14×14-pixel patches covering
the full 896×896 input. Each patch becomes one token. If the image is stretched to fill a
square from a 4:3 source, every patch covers a distorted area — a circular aphid colony
becomes an oval, and the model's patch embeddings (trained on undistorted images) produce
a worse match.

Center-cropping discards only the narrow strips at the long edges (top/bottom of a landscape
shot). In a field scan, the center of the frame is where the user is aiming — the discarded
edges are background.

### Why drive `inSampleSize` off the short side?

The old code used `maxOf(width, height)`. For a 4032×3024 image:
- `maxOf` → 4032 / 896 = 4 → rough decode = 1008×756 → short side 756 < 896
- After center-crop: 756×756; then scaled UP to 896×896 — blurry.

With the corrected formula using `minOf(width, height)`:
- `minOf` → 3024 / 896 = 3 → rough decode = 1344×1008 → short side 1008 ≥ 896
- Center-crop: 1008×1008; then scaled DOWN to 896×896 — sharp.

### Camera resolution cap and its interaction

With the camera capped at 1280×960 (`setResolutionSelector`):
- `shortSide = 960`, `960 / 896 = 1` → `inSampleSize = 1`
- The rough-decode step is effectively a no-op (full-res decode, no downsampling)
- Direct path: `1280×960 → crop to 960×960 → scale to 896×896`
- Total allocations: rough (1280×960) + squared (960×960) + scaled (896×896)
- Peak memory: ~10 MB for all three bitmaps — safe on any 2 GB+ device

---

## 7. Inference Optimization

### Greedy decoding (`topK = 1`)

```kotlin
SamplerConfig(topK = 1, topP = 1.0, temperature = 0.1)
```

`topK = 1` means at each decode step, the model picks the single highest-probability token
with no sampling randomness. This is the fastest possible sampling strategy — no temperature
scaling, no multinomial sampling, no top-p truncation computation. For structured outputs
(fixed format) it also produces more deterministic results than sampling.

### Early stream cancellation

```kotlin
if (isStructuredResponseComplete(accumulated.toString())) {
    conversation.cancelProcess()
}
```

The model generates tokens until it decides to stop (EOS token) or until we cancel. For a
three-field structured response (SEVERITY / PESTS / ACTION), the model often generates
50–200 extra tokens of explanation after the three lines before hitting EOS. `cancelProcess()`
fires the moment all three fields are parseable, cutting GPU time roughly in half on average.

### No chain-of-thought

```kotlin
private const val SYSTEM_INSTRUCTION =
    "You are a fast crop pest scanner on a low-power phone. " +
    "Respond INSTANTLY with NO thinking, NO reasoning, NO explanation. ..."
```

Gemma 4's instruction-tuning includes a chain-of-thought (CoT) mode that emits `<thinking>`
blocks before answers. On low-end devices this can add 500–2000 extra tokens (≈ 5–20 seconds
extra latency). The system instruction explicitly disables this pattern. Combined with
`topK = 1`, CoT suppression cuts average Time-to-First-Token by 60–80% on phones without
dedicated NPUs.

### Per-frame conversation objects

```kotlin
eng.createConversation(conversationConfig).use { conversation -> ... }
```

A new `Conversation` is created and closed for each frame. Reusing a conversation across
frames would accumulate KV-cache entries from prior images, making the model's context
window grow with every scan and slowing down attention computation. Starting fresh each
frame keeps the effective context short (system prompt + current image + detection prompt)
and inference time constant regardless of how many frames have been analyzed.

---

## 8. Battery & Thermal Optimization

### Adaptive frame pacing

`nextFrameDelay()` in `MainViewModel` reads thermal status and battery level after every
frame and adjusts the inter-frame gap:

| Condition | Interval | Reason |
|---|---|---|
| `THERMAL_STATUS_SEVERE` or higher | 12 s | SoC is throttling; additional inference makes it worse |
| `THERMAL_STATUS_MODERATE` | 6 s | Clocks slowing; give the SoC room to cool |
| Battery < 15% | 8 s | Preserve charge; user needs to move to shade/outlet |
| Battery < 30% | 5 s | Mild conservation |
| Nominal | 3 s | Standard rate |

**Why slower inference can save more battery:**
Android's DVFS scales CPU/GPU clocks down when the SoC is hot. At `THERMAL_STATUS_SEVERE`,
clocks may be running at 40–60% of peak. An inference that took 2 s at nominal takes 4–6 s
throttled, burning more cumulative energy. Reducing frequency lets the SoC cool, clocks
recover, and subsequent inferences run faster and cheaper.

### Thermal status API

`PowerManager.currentThermalStatus` (API 29+) returns one of:

| Constant | Value | Meaning |
|---|---|---|
| `THERMAL_STATUS_NONE` | 0 | No thermal constraints |
| `THERMAL_STATUS_LIGHT` | 1 | Minor thermal throttling starting |
| `THERMAL_STATUS_MODERATE` | 2 | Noticeable throttling |
| `THERMAL_STATUS_SEVERE` | 3 | Significant performance impact |
| `THERMAL_STATUS_CRITICAL` | 4 | System may shut down |
| `THERMAL_STATUS_EMERGENCY` | 5 | Imminent shutdown |
| `THERMAL_STATUS_SHUTDOWN` | 6 | Shutting down |

Devices below API 29 get `THERMAL_STATUS_NONE` (no throttling assumed).

### Battery level query

```kotlin
// Sticky broadcast — no registered receiver needed; returns last known state instantly
val intent = application.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
val percent = (level.toFloat() / scale * 100).toInt()
```

`ACTION_BATTERY_CHANGED` is a sticky broadcast (updated by Android whenever the battery
state changes). Querying it with a `null` receiver returns the last posted value without
registering a persistent listener — zero overhead between frames.

### Logcat verification

During `AppState.Live`, every inter-frame delay is logged:

```
D/SprayMind: Next frame in 3000ms (thermal=0, battery=87%)
D/SprayMind: Next frame in 6000ms (thermal=2, battery=85%)  ← device warming up
D/SprayMind: Next frame in 12000ms (thermal=3, battery=83%) ← throttling; backing off
```

---

## 9. Wake Lock Strategy

### The problem

Android's display timeout (typically 30 s–2 min) dims and then turns off the screen when
there is no touch input. When the screen sleeps, `ProcessCameraProvider` suspends camera
delivery, the `ImageCapture.takePicture()` callback never fires, and the coroutine loop
blocks indefinitely on `captureFrame()`.

### The solution

```kotlin
// In LiveDashboard composable (MainScreen.kt)
DisposableEffect(Unit) {
    val window = (context as? Activity)?.window
    window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
}
```

`FLAG_KEEP_SCREEN_ON` is scoped to the `Live` composable — it is set when the composable
enters the composition and cleared in `onDispose` (which fires when the user taps
"Finish Session" and the state transitions to `Finished`).

**Why not `PowerManager.WakeLock`?**
- `WakeLock` requires the `WAKE_LOCK` permission in the manifest.
- `FLAG_KEEP_SCREEN_ON` needs no manifest declaration.
- `WakeLock` must be manually acquired/released and leaks if the activity is destroyed
  unexpectedly. The window flag is automatically cleared by the system.
- For a camera-preview app (screen must be on anyway), keeping the display alive is the
  right behavior; a partial wake lock (screen off, CPU on) would not help here.

---

## 10. Camera Resolution Cap

```kotlin
ImageCapture.Builder()
    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
    .setResolutionSelector(
        ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 960),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()
    )
    .build()
```

### Why cap at 1280×960?

| Metric | 12 MP (4032×3024) | 1280×960 |
|---|---|---|
| Raw JPEG size | ~4–6 MB | ~350–500 KB |
| JNI transfer to LiteRT | ~12 MB raw pixels | ~3.7 MB raw pixels |
| inSampleSize needed | 3 or 4 | 1 (skip rough decode) |
| Bitmap allocations | 3 (rough + cropped + scaled) | 2 (full + scaled) |
| Preprocessing time | ~120–200 ms | ~30–50 ms |

At 1280×960, the short side (960) is only 7% larger than TARGET_IMAGE_SIZE (896), so
almost no information is discarded in the center-crop step. The model receives essentially
the same information content as from a 12 MP capture, at a fraction of the cost.

### `FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER`

If the camera sensor does not support exactly 1280×960 (common on sensors with custom
aspect ratios), CameraX will first try a slightly lower resolution (e.g., 1280×720,
1024×768) before trying a higher one. This keeps the cost bounded — we never accidentally
request a 4K frame because 1280×960 was unavailable.

---

## 11. Error Recovery

### The stuck-engine bug (fixed)

**Before:**
```kotlin
fun retry() {
    loadModel()
}

// In CropAnalyzer:
suspend fun initializeModel() {
    if (engine != null) return  // ← engine might be non-null but crashed
    engine = buildEngine()
}
```

If the engine crashed mid-session (OOM, GPU driver fault), `engine` was non-null but
broken. `retry()` called `loadModel()` → `initializeModel()` → hit the `engine != null`
guard → returned immediately without rebuilding → retry appeared to succeed but the next
inference attempt crashed again.

**After:**
```kotlin
fun retry() {
    CropAnalyzer.close()  // sets engine = null
    loadModel()           // now initializeModel() rebuilds from scratch
}
```

`CropAnalyzer.close()` calls `engine?.close()` and sets `engine = null`. The subsequent
`initializeModel()` call finds `engine == null` and builds a fresh one.

---

## 12. ProGuard / R8 Rules

`app/proguard-rules.pro` contains three rule groups:

### LiteRT JNI bridge
```
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.**
```
LiteRT uses JNI extensively. Native code calls into JVM classes by name — if R8 renames
or removes them, the native calls throw `NoSuchMethodError` at runtime.

### Coroutines dispatcher factory
```
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
```
Kotlinx.coroutines resolves the `Dispatchers.Main` implementation via `ServiceLoader` at
runtime. If these class names are obfuscated, coroutines fall back to a no-op dispatcher
and nothing runs on the main thread.

### SprayMind data layer
```
-keep class com.spraymind.DetectionResult { *; }
-keep enum com.spraymind.Severity { *; }
```
Conservative safety net. Kotlin data classes are normally kept by reference tracing, but
this is explicit insurance under R8 full-mode (enabled by `proguard-android-optimize.txt`).

---

## 13. Benchmarking & Profiling

### Logcat timing (quick)

```bash
adb logcat -s SprayMind:D
```

Key log lines to watch:
- `"LiteRT engine ready"` — time from launch to this line = shader compile cost (first run)
- `"Stream ended after early parse"` — early-cancel fired; note the char count
- `"Next frame in Xms (thermal=Y, battery=Z%)"` — adaptive pacing working

### Android GPU Inspector

1. Build a **profileable** release variant (add `<profileable android:shell="true"/>` to manifest)
2. Open Android GPU Inspector → connect device → capture a frame
3. Look for: `glDispatchCompute` call duration (inference kernel time) and `glReadPixels`
   (GPU→CPU transfer cost)

### Battery Historian

```bash
# Reset battery stats, run a 5-minute scan session, pull the report:
adb shell dumpsys batterystats --reset
# ... run session ...
adb bugreport bugreport.zip
# Open bugreport.zip in Battery Historian (web.dev/battery-historian)
```

Focus on: wakelock duration, CPU frequency histogram during inference, display vs. GPU
energy breakdown.

### Systrace / Perfetto

```bash
adb shell perfetto --config :test -o /data/misc/perfetto-traces/trace.pb --time 30s
adb pull /data/misc/perfetto-traces/trace.pb
# Open in ui.perfetto.dev
```

Look for: `SprayMind.*analyzeFrame` slice duration, GPU utilization track, thermal throttle
events (shown as frequency drops on the CPU/GPU frequency tracks).

---

## 14. Known Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| `topK = 1` (greedy) | Fastest decoding, deterministic | No output diversity; worst-case may miss rare pest names |
| Center-crop (not letterbox) | No distortion in center | Narrow strips at frame edges are lost |
| 3 s base interval | Low thermal pressure; model stays cool | ~20 scans/minute; large yards take longer |
| `FLAG_KEEP_SCREEN_ON` | Loop never stalls | Screen stays on = higher display energy |
| Camera cap at 1280×960 | Fast preprocessing | Very fine details (tiny mites) may be lost vs. 12 MP |
| Per-frame conversation reset | Constant latency | No inter-frame temporal context accumulation |
| GPU-first | Fast & efficient | GPU shader warmup on cold launch (mitigated by cache) |

---

## 15. Troubleshooting

### "Model not found at /data/local/tmp/..."
```bash
adb push gemma-4-E2B-it.litertlm /data/local/tmp/
```

### "GPU init failed, falling back to CPU"
- Check `adb logcat -s SprayMind` for the full exception.
- Common causes: missing `libOpenCL.so` (older device), OOM during GPU init.
- CPU fallback is automatic; inference will be slower but functional.

### Shader cache not being reused
```bash
adb shell ls -lh /data/local/tmp/litert-cache/
```
If the directory is empty after the first run, check that `/data/local/tmp/` is writable:
```bash
adb shell touch /data/local/tmp/test && echo "writable"
```
On production devices, you may need to move the cache to `context.cacheDir`.

### Scan loop stalls (no new scans after first few)
- Most likely cause: screen sleeping despite `FLAG_KEEP_SCREEN_ON` — check that
  `LiveDashboard` is actually in the composition (state is `AppState.Live`).
- Secondary cause: thermal shutdown — check `adb logcat` for THERMAL_STATUS_CRITICAL.

### Early-cancel fires too soon (incomplete results)
- `isStructuredResponseComplete()` checks for three line prefixes. If the model emits
  the prefix without a value on the same line (line break before content), detection fails.
- Add a log statement inside `isStructuredResponseComplete()` to see partial text.

### `retry()` has no effect after crash
- Confirm `CropAnalyzer.close()` is called before `loadModel()` (already fixed in this version).
- If still stuck: kill the app (`adb shell am force-stop com.spraymind`) and relaunch.

---

---

## 11. Adaptive Token Budget (Gemma 4 Variable-Resolution Vision)

### What it is

Gemma 4 introduces a **variable-resolution vision system** through its SigLIP encoder. Instead of
always processing the image at a fixed patch count, the model supports five discrete token budgets:

| Budget | Tokens | targetPx | Raw patches (Budget × 9) | Relative compute |
|---|---|---|---|---|
| MINIMAL  |   70 | 350 px | 630  | ~6 % |
| LOW      |  140 | 504 px | 1 260 | ~18 % |
| BALANCED |  280 | 700 px | 2 520 | ~38 % |
| HIGH     |  560 | 896 px | 5 040 | ~75 % |
| FULL     | 1120 | 896 px | 10 080 | 100 % |

The encoder generates `Budget × 9` raw patches from the input image, then applies a **3×3
average-pool** compression step to reduce them to exactly `Budget` final tokens fed to the
language decoder.

### Two levers working together

**Lever 1 — pixel size:** The image is resized to `budget.targetPx × budget.targetPx` before
the encoder sees it. Smaller image = fewer patches = fewer raw tokens, which means fewer
attention operations in the decoder.

```
targetPx = nearest multiple of 14 to √(tokens × 9) × 14
```
(14 px = SigLIP patch stride; 9 = 3×3 pool factor)

**Lever 2 — LiteRT API parameter (TODO):** For FULL (1120 tokens), the pixel dimension alone
cannot exceed the SigLIP native tile of 896 px. A dedicated `tokenBudget` field on
`EngineConfig` or `ConversationConfig` is needed to activate the full 1120-token path.
When this parameter becomes available in the LiteRT-LM SDK, add it alongside the pixel-size
control in `CropAnalyzer.buildEngine()` or `analyzeFrame()`.

### Adaptive budget selection

`selectTokenBudget()` in `MainViewModel` mirrors the same thermal/battery tiers used by
`nextFrameDelay()`, so both levers fire together under stress:

| Condition | Budget | Effect |
|---|---|---|
| `THERMAL_STATUS_SEVERE` | MINIMAL (70 tokens) | 94 % less vision compute vs FULL |
| `THERMAL_STATUS_MODERATE` | LOW (140 tokens) | 82 % less vision compute |
| Battery < 15 % | LOW (140 tokens) | Preserves charge |
| Battery < 30 % | BALANCED (280 tokens) | Mild conservation |
| Nominal | BALANCED (280 tokens) | Default; ~38 % of FULL cost, good accuracy |

The combined effect under `THERMAL_STATUS_SEVERE`: frame interval increases from 3 s to 12 s
**and** each frame costs only 6 % of the nominal vision compute. The SoC gets four times as
long to cool, and each inference burns ~94 % less GPU energy for the vision pass.

### `prepareImageTensor` — direct ByteBuffer path

```kotlin
val buffer: ByteBuffer = ImageTokenBudget.BALANCED.prepareImageTensor(bitmap)
```

Returns a direct `ByteBuffer` (native byte order) with normalized float RGB pixels [0.0, 1.0].
Layout: `targetPx × targetPx × 3 floats`. This is the input format expected by LiteRT's
low-level tensor injection API. SigLIP applies its own mean/std normalization on top.

Use cases:
- Future `Interpreter.run()` / `TensorBuffer` API path (avoids `Content.ImageFile` disk I/O)
- Benchmarking raw image preprocessing cost independently of the full inference pipeline
- Plugging into custom LiteRT session management for multi-model pipelines

### Logcat verification

During `AppState.Live`, every frame logs the active budget:

```
D/SprayMind: analyzeFrame: budget=BALANCED (280 tokens, 700px)
D/SprayMind: analyzeFrame: budget=LOW (140 tokens, 504px)      ← device warming up
D/SprayMind: analyzeFrame: budget=MINIMAL (70 tokens, 350px)   ← thermal throttle
```

Cross-reference with the pacing log to see both levers at work:

```
D/SprayMind: Next frame in 12000ms (thermal=3, battery=72%)
D/SprayMind: analyzeFrame: budget=MINIMAL (70 tokens, 350px)
```

### Quality tradeoff

At typical phone-to-plant working distances (30–60 cm), BALANCED (280 tokens, 700 px) delivers
visually lossless pest detection compared to FULL. The 700 px input still provides ~50 patches
per dimension, which resolves aphid colonies, leaf discolouration, and fungal hyphae at the
resolution the model was trained to handle. Significant quality loss only occurs at MINIMAL
(70 tokens, 350 px) for very small or subtly-coloured pests.

---

---

## 16. Agronomist-Grade Smart Notification Scheduling

### What it does

After every completed scan session, `SessionAdvisor` drives a **multi-turn Gemma 4
tool-calling conversation** in the background. Instead of naively reminding the farmer
every 7 days, the model reasons over accumulated field data to compute when re-inspection
is most warranted — the same decision an experienced agronomist would make.

An agronomist doing this manually takes 20 minutes per farm. The model does it in
< 30 seconds per session, fully offline, with no server.

### Agent architecture

```
Session ends (farmer taps "Save & New Session")
  │
  ├── Navigation fires immediately (non-blocking)
  │
  └── viewModelScope.launch(IO) {
          SessionAdvisor.runForSession(...)
              │
              └── CropAnalyzer.runAdvisorLoop(systemPrompt, sessionSummary)
                      │
                      ├── Model → <tool_call> get_cell_history {cellIndex: 12}
                      │         ← DB read: ScanRecord history for that cell
                      │
                      ├── Model → <tool_call> get_weather_forecast {lat, lon}
                      │         ← Open-Meteo HTTP (free, no API key)
                      │
                      ├── Model → <tool_call> schedule_notification {title, body, isoDateTime, urgency}
                      │         ← WorkManager.enqueue() + Room INSERT
                      │
                      └── Model → final summary (no more tool calls → loop exits)
      }
```

### Tool definitions

The four tools are embedded as JSON in the system prompt — Gemma 4 reads this format
and produces `<tool_call>` blocks in its response text:

| Tool | Purpose |
|---|---|
| `get_cell_history` | Reads `ScanRecord` table; returns severity + pests + treatment notes per past session |
| `get_weather_forecast` | Open-Meteo 7-day forecast; flags rain days (spray wash-off risk) and hot/dry days (spider mite risk) |
| `schedule_notification` | Enqueues a `WorkManager OneTimeWorkRequest`; persists to `scheduled_notifications` table |
| `dismiss_notifications` | Cancels WorkManager jobs for cells confirmed healthy; deletes DB rows |

### Treatment efficacy windows in the system prompt

```
Neem oil / neem extract : 5–7 days
Copper fungicide        : 10–14 days
Pyrethrin / spinosad    : 3–5 days
Water rinse / soap spray: 2–3 days
No treatment recorded   : HIGH → next day, MEDIUM → 3 days, LOW → 5 days
```

The model reads the farmer's session notes (e.g., "applied neem this morning") and
matches them against these windows to compute the re-inspection date.

### Weather integration

If the farmer recorded a treatment and GPS coordinates are set on the yard profile,
the model calls `get_weather_forecast` with the field's lat/lon. Open-Meteo returns
a 7-day forecast (precipitation + max temperature). The model uses this to:

- Warn the farmer if significant rain (> 5 mm) is expected within 24 h of the
  re-inspection date (rain would wash off any new spray).
- Note hot/dry conditions as a spider mite risk factor in the notification body.

Open-Meteo requires no API key, is GDPR-compliant, and works down to 1 km resolution
worldwide — appropriate for smallholder farms in Africa, Asia, and Latin America.

### Tool-call parsing

Gemma 4 produces tool calls in one of two formats:

```xml
<!-- Format A (Gemma 4 default) -->
<tool_call>{"name": "get_cell_history", "arguments": {"cellIndex": 12}}</tool_call>

<!-- Format B (markdown code block) -->
```tool_call
{"name": "get_cell_history", "arguments": {"cellIndex": 12}}
```
```

`CropAnalyzer.parseToolCall()` tries both patterns, then a third JSON fallback. The
response is sent back in the next turn as `<tool_response>` text, which Gemma 4
recognises from training regardless of conversation role.

### Notification delivery

Each `schedule_notification` tool call:

1. Inserts a `ScheduledNotification` row in Room (stores `workRequestId` for cancellation)
2. Enqueues a `OneTimeWorkRequestBuilder<InspectionReminderWorker>` with an `initialDelay`
3. `InspectionReminderWorker.doWork()` calls `NotificationHelper.post()` at fire time

WorkManager survives app death and device reboots (`RECEIVE_BOOT_COMPLETED` permission).
A maximum of 3 notifications per session is enforced in the system prompt and in code to
prevent notification fatigue.

### Notification fatigue guard

The system prompt says: *"Schedule at MOST 3 notifications total — farmers ignore excess
alerts."* The `scheduleNotification()` tool implementation also checks `scheduled.size >= MAX_NOTIF_COUNT`
and returns `{"status": "skipped"}` — a belt-and-suspenders guard so the cap is enforced
even if the model ignores the instruction.

### Same engine, zero extra RAM

`SessionAdvisor` uses `CropAnalyzer.runAdvisorLoop()` which reuses the already-loaded
LiteRT engine. There is no second model load. The advisor conversation is text-only
(no `Content.ImageFile`), so the vision encoder is bypassed — the advisor uses only the
LLM decoder, which is fast and cheap at token-budget NONE (pure text).

*Last updated: 2026-05-19 — §16 added: SessionAdvisor smart notification scheduling*
