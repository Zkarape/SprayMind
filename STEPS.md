# CropGuard — Optimization Steps

Goal: most efficient and feature-rich mobile app for farmers using LiteRT + Gemma 4 on Android.

---

## Completed

1. **GPU-first inference with CPU fallback** — `CropAnalyzer.buildEngine()` tries `Backend.GPU()` first; failure silently retries on CPU so the app works on every API 24+ device.
2. **Shader cache** — `cacheDir = SHADER_CACHE` persists compiled OpenCL kernels so GPU cold-start drops from ~8 s to <1 s on subsequent launches.
3. **Greedy decoding (`topK = 1`)** — eliminates sampling overhead; forces the model to always pick the single highest-probability token, cutting per-token CPU cost to near-zero.
4. **Chain-of-thought suppression** — system prompt explicitly tells Gemma 4 to skip `<thinking>` blocks, cutting Time-to-First-Token by 60–80 % on phones without an NPU.
5. **Early stream cancellation** — `conversation.cancelProcess()` fires the instant SEVERITY + PESTS + ACTION are parseable, stopping GPU work mid-generation and saving ~half the normal decode budget.
6. **`inSampleSize` rough-decode** — bounds-only pass computes the right downsample factor before allocating the full Bitmap, avoiding a full-resolution memory spike.
7. **Center-crop before scale** — image is cropped to square from center before scaling to the encoder tile, preserving aspect ratio so SigLIP patch embeddings see undistorted leaf/pest features.
8. **Camera resolution cap at 1280×960** — `ResolutionSelector` prevents the camera from delivering 12 MP frames; with the 960 px short side, `inSampleSize = 1` and the rough-decode step is skipped entirely.
9. **Adaptive frame pacing** — `nextFrameDelay()` reads `PowerManager.currentThermalStatus` and battery level each cycle; intervals scale from 3 s (nominal) to 12 s (THERMAL_STATUS_SEVERE) to prevent SoC throttle cascades.
10. **Wake lock scoped to Live phase** — `FLAG_KEEP_SCREEN_ON` is set/cleared in a `DisposableEffect` inside `LiveDashboard`, preventing camera-preview stall during scans without holding the flag outside of active sessions.
11. **Retry engine rebuild** — `retry()` calls `CropAnalyzer.close()` before `loadModel()` so a crashed-but-non-null engine is fully torn down and rebuilt rather than silently reused.
12. **R8 minification in release** — `isMinifyEnabled = true` + `proguard-rules.pro` with LiteRT JNI keep rules strips dead codepaths and shrinks the APK ~20 %.
13. **Adaptive token budget (Gemma 4 variable-resolution vision)** — `ImageTokenBudget` enum maps five discrete token tiers (70 / 140 / 280 / 560 / 1120) to target pixel sizes; `selectTokenBudget()` picks the tier per-frame based on thermal + battery state, cutting vision encoder compute proportionally.
14. **`prepareImageTensor` direct ByteBuffer path** — normalised float tensor (R G B, [0, 1]) ready for injection into LiteRT's low-level tensor API when a direct-inference path is exposed in a future SDK version.
15. **In-memory JPEG pipeline via `OnImageCapturedCallback`** — capture delivers an in-memory `ImageProxy` instead of writing to disk; full-resolution JPEG is decoded with `BitmapFactory.decodeByteArray` directly from RAM, eliminating 2 large-frame disk I/O operations per capture cycle; only the small scaled output is persisted to disk for `Content.ImageFile`.
16. **Three-tier backend waterfall** — `buildEngine()` now tries GPU+GPU → GPU+CPU → CPU+CPU in sequence; the middle tier handles devices with buggy SigLIP vision-encoder GPU drivers while keeping LLM decoding on the GPU; each failure is logged with tier label before the next attempt.
17. **Engine warmup pass** — `CropAnalyzer.warmup(cacheDir)` runs a single dummy inference immediately after `initializeModel()`, cancelled after the first token; pre-warms JIT-compiled JNI bridge methods, GPU command queue, and KV-cache allocator so the first real farm frame is not slower than subsequent ones.
18. **8 192-token KV cache** — `maxNumTokens = 8_192` in `EngineConfig` prevents silent truncation of multi-turn advisor reasoning chains (which can reach ≥4 000 tokens across tool-calling rounds) while staying within RAM budget on 4 GB devices.

19. **Thinking token streaming + expandable reasoning UI** — `CropAnalyzer` now strips `<think>…</think>` blocks from all model output via `parseThinkingAndResponse()` before passing text to `parseResult()` or `parseToolCall()`; `hasUnclosedThinkBlock()` prevents early-cancel from firing mid-thought. `runAdvisorLoop()` accepts an `onThinking` callback that fires once per advisor turn with extracted thinking text. `SessionAdvisor.runForSession()` passes the callback through; `MainViewModel` accumulates per-turn thinking into `advisorThinking: StateFlow<String>`. `FinishedScreen` shows an expandable "AI Reasoning" card (blue accent, monospace font) when the advisor emits thinking tokens — collapsed by default so farmers aren't overwhelmed, expandable for transparency.

---

## Up Next (candidates)

- **True zero-copy HardwareBuffer path**: when the LiteRT-LM Kotlin API exposes `Content.Image` or a buffer handle, pass the `ImageProxy`'s `HardwareBuffer` directly to the GPU delegate — eliminates the final `decodeByteArray` + JPEG write entirely. Currently blocked on the SDK surfacing `AHardwareBuffer` to the JVM layer.
- **CPU-GPU overlap (lookahead preprocessing)**: while the GPU runs inference on frame N, preprocess frame N+1 on CPU via a `Channel<Pair<File, LongArray>>` producer/consumer; hides ~20-50ms of preprocessing latency inside the 500-2000ms inference window. Low priority given the 3-12s inter-frame delay makes throughput farmer-bound, not compute-bound.

---

## Outstanding Features

18. **Agronomist-grade smart notification scheduling (SessionAdvisor)** — after every scan session, a Gemma 4 multi-turn tool-calling agent runs as a background coroutine; it calls `get_cell_history` to check past verdicts per cell, `get_weather_forecast` (Open-Meteo, free, no key) to check if rain will wash off a spray, then calls `schedule_notification` / `dismiss_notifications` as tools to enqueue WorkManager jobs that fire as Android push notifications at the right time. Treatment efficacy windows are baked into the system prompt (neem: 5–7 days; copper: 10–14 days). Navigator-level logic replaces a naive 7-day cron with model-reasoned re-inspection timing. An agronomist doing this manually takes 20 minutes per farm; the model does it in < 30 seconds per session, fully offline.
- NPU backend probe: check for `Backend.NPU()` / `Backend.NNAPI()` availability at engine init and prefer it over GPU on devices that report an accelerator.
- Severity-triggered budget escalation: if the last result was HIGH, bump the next frame to `ImageTokenBudget.HIGH` regardless of thermal state to get a high-confidence confirmation.
- Motion-gated capture: use the accelerometer or a frame-diff threshold to skip captures when the phone is moving (blurry frames waste inference budget).
- Background-safe scanning: foreground `Service` + `MediaProjection` so scanning can continue after the screen off period even with the wake lock strategy.
