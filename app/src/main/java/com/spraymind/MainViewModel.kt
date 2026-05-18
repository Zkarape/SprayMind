package com.spraymind

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spraymind.db.SprayMindDatabase
import com.spraymind.db.ScanRecord
import com.spraymind.db.YardProfile
import com.spraymind.db.YardSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class AppState {
    object Downloading : AppState()
    object Initializing : AppState()
    // Shows saved yards; farmer picks an existing field or creates a new one.
    data class YardSelect(val profiles: List<YardProfile>) : AppState()
    // Form to name and dimension a yard. `existing` is non-null when editing a saved yard.
    data class YardSetup(val existing: YardProfile? = null) : AppState()
    object Live : AppState()
    object Finished : AppState()
    data class Error(val message: String) : AppState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = SprayMindDatabase.getInstance(application)

    // Resolution capped at 1280×960 — see LITERT_GEMMA4_GUIDE.md §10.
    val imageCapture: ImageCapture = ImageCapture.Builder()
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

    private val _state = MutableStateFlow<AppState>(AppState.Downloading)
    val state: StateFlow<AppState> = _state

    val loadedModelName = MutableStateFlow<String?>(null)

    private val _currentResult = MutableStateFlow<DetectionResult?>(null)
    val currentResult: StateFlow<DetectionResult?> = _currentResult

    private val _scanHistory = MutableStateFlow<List<DetectionResult>>(emptyList())
    val scanHistory: StateFlow<List<DetectionResult>> = _scanHistory

    private val _yardWidth  = MutableStateFlow(0)
    val yardWidth: StateFlow<Int> = _yardWidth

    private val _yardHeight = MutableStateFlow(0)
    val yardHeight: StateFlow<Int> = _yardHeight

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing

    private val _frameCount = MutableStateFlow(0)
    val frameCount: StateFlow<Int> = _frameCount

    // Frames the dHash deduplicator skipped since the last accepted frame.
    // Shown in the UI overlay so the farmer knows the app is alive even when nothing changes.
    private val _skippedFrameCount = MutableStateFlow(0)
    val skippedFrameCount: StateFlow<Int> = _skippedFrameCount

    // The yard active in the current session — used on the Finished screen.
    private val _activeProfile = MutableStateFlow<YardProfile?>(null)
    val activeProfile: StateFlow<YardProfile?> = _activeProfile

    // The session that completed just before the current one — used for before/after comparison.
    private val _previousSession = MutableStateFlow<YardSession?>(null)
    val previousSession: StateFlow<YardSession?> = _previousSession

    // Accumulates <think>…</think> text from the advisor's multi-turn reasoning.
    // Shown as an expandable card on FinishedScreen so farmers can see the AI's logic.
    private val _advisorThinking = MutableStateFlow("")
    val advisorThinking: StateFlow<String> = _advisorThinking

    // In-memory session state
    private var activeSessionId: Long?   = null
    private var lastFrameHash: LongArray? = null   // 256-bit dHash of the last processed frame
    private var pendingSkippedFrames     = 0       // accumulated between persisted scans

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var analysisJob: Job? = null

    private val powerManager = application.getSystemService(Context.POWER_SERVICE) as PowerManager

    companion object {
        private const val BASE_FRAME_INTERVAL_MS = 3_000L
        private const val CAMERA_BIND_DELAY_MS   = 1_500L
        // When a frame is skipped as identical, retry quickly — the farmer may be moving
        // the camera to a new section and we want to pick up the change fast.
        private const val SKIPPED_FRAME_RETRY_MS = 1_000L
    }

    init {
        loadModel()
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    private fun loadModel() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.value = AppState.Downloading
                CropAnalyzer.downloadModel()
                loadedModelName.value = CropAnalyzer.modelSlug

                _state.value = AppState.Initializing
                CropAnalyzer.initializeModel()
                // Warmup runs during Initializing so the first real farm frame is fast.
                CropAnalyzer.warmup(getApplication<Application>().cacheDir)

                // After init, jump straight to yard selection if any yards are saved.
                val profiles = db.yardProfileDao().getAll()
                _state.value = if (profiles.isEmpty()) AppState.YardSetup()
                               else AppState.YardSelect(profiles)
            } catch (e: Exception) {
                Log.e("SprayMind", "Model load failed: ${e::class.simpleName}: ${e.message}", e)
                _state.value = AppState.Error("${e::class.simpleName}: ${e.message}")
            }
        }
    }

    fun retry() {
        CropAnalyzer.close()
        loadModel()
    }

    // ─── Yard selection ───────────────────────────────────────────────────────

    fun selectExistingYard(profile: YardProfile) {
        // Pre-fill YardSetup with saved dimensions — farmer just confirms or edits.
        _state.value = AppState.YardSetup(existing = profile)
    }

    fun createNewYard() {
        _state.value = AppState.YardSetup(existing = null)
    }

    // Called from YardSetupScreen when "Start Scanning" is tapped.
    // Upserts the yard profile, loads the previous session for comparison, creates a new session.
    fun confirmYardSetup(
        name: String, width: Int, height: Int,
        lat: Double?, lon: Double?,
        existing: YardProfile?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val profile: YardProfile = if (existing != null) {
                existing.copy(
                    name         = name.ifBlank { existing.name },
                    widthMeters  = width,
                    heightMeters = height,
                    latitudeDeg  = lat ?: existing.latitudeDeg,
                    longitudeDeg = lon ?: existing.longitudeDeg,
                    lastVisitAt  = System.currentTimeMillis()
                ).also { db.yardProfileDao().update(it) }
            } else {
                val id = db.yardProfileDao().insert(
                    YardProfile(
                        name         = name,
                        widthMeters  = width,
                        heightMeters = height,
                        latitudeDeg  = lat,
                        longitudeDeg = lon
                    )
                )
                YardProfile(id = id, name = name, widthMeters = width, heightMeters = height,
                            latitudeDeg = lat, longitudeDeg = lon)
            }

            // Load the most recent finished session for this yard so the Finished screen
            // can show a before/after comparison (e.g. was pesticide effective?).
            _previousSession.value = db.yardSessionDao().getLastTwo(profile.id).firstOrNull()

            val sessionId = db.yardSessionDao().insert(YardSession(yardProfileId = profile.id))
            activeSessionId = sessionId
            _activeProfile.value = profile
            _yardWidth.value  = width
            _yardHeight.value = height
            _state.value = AppState.Live
        }
    }

    // ─── Analysis loop ────────────────────────────────────────────────────────

    fun startAnalysis(cacheDir: File) {
        lastFrameHash        = null
        pendingSkippedFrames = 0
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch(Dispatchers.IO) {
            delay(CAMERA_BIND_DELAY_MS)
            while (isActive) {
                var capturedFile: File? = null
                var skipped = false
                try {
                    val budget = selectTokenBudget()
                    val (file, hash) = captureFrame(cacheDir, budget)
                    capturedFile = file

                    val prev = lastFrameHash
                    if (prev != null && FrameHasher.isSimilar(prev, hash)) {
                        // Frame is nearly identical to the last processed frame — skip inference.
                        val dist = FrameHasher.hammingDistance(prev, hash)
                        pendingSkippedFrames++
                        _skippedFrameCount.value = pendingSkippedFrames
                        Log.d("SprayMind", "Frame skipped — Hamming=$dist, total skipped=$pendingSkippedFrames")
                        skipped = true
                    } else {
                        lastFrameHash = hash
                        _isAnalyzing.value = true
                        val result = CropAnalyzer.analyzeFrame(capturedFile.absolutePath, budget)

                        // Persist to DB in real-time so data survives even if the session
                        // ends unexpectedly (app killed, phone battery dies mid-scan).
                        activeSessionId?.let { sid ->
                            db.scanRecordDao().insert(
                                ScanRecord(
                                    sessionId    = sid,
                                    squareMeter  = _frameCount.value + 1,
                                    severity     = result.severity.name,
                                    pests        = result.pests,
                                    action       = result.action,
                                    pressureBar  = result.pressureBar,
                                    skippedFrames = pendingSkippedFrames
                                )
                            )
                        }
                        pendingSkippedFrames    = 0
                        _skippedFrameCount.value = 0
                        _currentResult.value     = result
                        _frameCount.value       += 1
                        _scanHistory.value       = _scanHistory.value + result
                    }
                } catch (e: Exception) {
                    Log.e("SprayMind", "Frame analysis error", e)
                } finally {
                    _isAnalyzing.value = false
                    capturedFile?.delete()
                }

                delay(if (skipped) SKIPPED_FRAME_RETRY_MS else nextFrameDelay())
            }
        }
    }

    fun stopAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        _isAnalyzing.value = false
    }

    // Sets AppState.Finished immediately so the farmer sees analytics quickly.
    // The DB write (with notes) is deferred to newSession() when the farmer is done reviewing.
    fun finish() {
        stopAnalysis()
        _state.value = AppState.Finished
    }

    // Finalises the current session in the DB (persists notes + summary stats),
    // navigates back to yard selection, then fires the SessionAdvisor as a
    // fire-and-forget background job. Navigation is never blocked by the advisor.
    fun newSession(notes: String = "") {
        // Capture before any state reset — the advisor coroutine needs these.
        val history           = _scanHistory.value
        val capturedSessionId = activeSessionId
        val capturedProfile   = _activeProfile.value
        val peak = history.maxByOrNull { it.severity.ordinal }?.severity ?: Severity.NONE
        val avg  = if (history.isEmpty()) 0f else history.map { it.pressureBar }.average().toFloat()

        // 1. DB finalisation + state reset + navigation.
        viewModelScope.launch(Dispatchers.IO) {
            capturedSessionId?.let { sid ->
                db.yardSessionDao().finish(
                    id           = sid,
                    finishedAt   = System.currentTimeMillis(),
                    peakSeverity = peak.name,
                    totalScans   = history.size,
                    avgPressure  = avg,
                    notes        = notes
                )
            }
            _scanHistory.value       = emptyList()
            _frameCount.value        = 0
            _currentResult.value     = null
            _skippedFrameCount.value = 0
            _advisorThinking.value   = ""
            activeSessionId          = null
            lastFrameHash            = null
            pendingSkippedFrames     = 0

            val profiles = db.yardProfileDao().getAll()
            _state.value = if (profiles.isEmpty()) AppState.YardSetup()
                           else AppState.YardSelect(profiles)
        }

        // 2. Advisor: runs after DB is written; does not block navigation.
        // Only fires when there are problem cells worth reasoning about.
        if (capturedProfile != null && capturedSessionId != null &&
            history.any { it.severity != Severity.NONE }) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    SessionAdvisor.runForSession(
                        sessionId   = capturedSessionId,
                        yardProfile = capturedProfile,
                        scanHistory = history,
                        notes       = notes,
                        db          = db,
                        context     = getApplication(),
                        onThinking  = { chunk ->
                            _advisorThinking.value = _advisorThinking.value +
                                if (_advisorThinking.value.isBlank()) chunk else "\n\n$chunk"
                        }
                    )
                }.onFailure { Log.e("SprayMind", "SessionAdvisor failed: ${it.message}", it) }
            }
        }
    }

    // ─── Adaptive token budget ────────────────────────────────────────────────

    private fun selectTokenBudget(): ImageTokenBudget {
        val thermal = thermalStatus()
        val battery = batteryLevel()
        return when {
            thermal >= PowerManager.THERMAL_STATUS_SEVERE   -> ImageTokenBudget.MINIMAL
            thermal >= PowerManager.THERMAL_STATUS_MODERATE -> ImageTokenBudget.LOW
            battery < 15                                     -> ImageTokenBudget.LOW
            battery < 30                                     -> ImageTokenBudget.BALANCED
            else                                             -> ImageTokenBudget.BALANCED
        }
    }

    // ─── Adaptive frame pacing ────────────────────────────────────────────────

    private fun nextFrameDelay(): Long {
        val thermal = thermalStatus()
        val battery = batteryLevel()
        return when {
            thermal >= PowerManager.THERMAL_STATUS_SEVERE   -> 12_000L
            thermal >= PowerManager.THERMAL_STATUS_MODERATE ->  6_000L
            battery < 15                                     ->  8_000L
            battery < 30                                     ->  5_000L
            else                                             -> BASE_FRAME_INTERVAL_MS
        }
    }

    @SuppressLint("NewApi")
    private fun thermalStatus(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            powerManager.currentThermalStatus
        else
            PowerManager.THERMAL_STATUS_NONE

    private fun batteryLevel(): Int {
        val intent = getApplication<Application>()
            .registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return (level.toFloat() / scale * 100).toInt()
    }

    // ─── Frame capture ────────────────────────────────────────────────────────

    private suspend fun captureFrame(cacheDir: File, budget: ImageTokenBudget): Pair<File, LongArray> {
        val dir  = File(cacheDir, "frames").also { it.mkdirs() }
        val file = File(dir, "frame_${System.currentTimeMillis()}.jpg")

        // Capture to an in-memory ImageProxy — the full-res JPEG stays in RAM, not on disk.
        val proxy = suspendCancellableCoroutine<ImageProxy> { cont ->
            imageCapture.takePicture(cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) = cont.resume(image)
                    override fun onError(e: ImageCaptureException)   = cont.resumeWithException(e)
                }
            )
        }

        val hash = proxy.use { encodeAndHash(it, file, budget.targetPx) }
        return Pair(file, hash)
    }

    // ─── Image preprocessing + hashing ───────────────────────────────────────
    //
    // Decodes JPEG bytes from the in-memory ImageProxy buffer (no disk read for the large
    // frame), center-crops to square, scales to targetPx×targetPx, computes dHash, then
    // writes only the small output JPEG to disk for LiteRT's Content.ImageFile path.
    private fun encodeAndHash(proxy: ImageProxy, outFile: File, targetPx: Int): LongArray {
        val buf   = proxy.planes[0].buffer
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }

        // Reading the JPEG header from memory is free — no disk seek.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return LongArray(4)

        val inSampleSize = (minOf(srcW, srcH) / targetPx).coerceAtLeast(1)
        val rough = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        ) ?: return LongArray(4)

        val rw = rough.width
        val rh = rough.height
        val cropSize = minOf(rw, rh)
        val squared: Bitmap = if (rw == rh) rough else {
            Bitmap.createBitmap(rough, (rw - cropSize) / 2, (rh - cropSize) / 2, cropSize, cropSize)
                .also { rough.recycle() }
        }

        val scaled = Bitmap.createScaledBitmap(squared, targetPx, targetPx, true)
        squared.recycle()

        val hash = FrameHasher.dhash(scaled)

        FileOutputStream(outFile).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 85, out) }
        scaled.recycle()

        return hash
    }

    override fun onCleared() {
        super.onCleared()
        analysisJob?.cancel()
        cameraExecutor.shutdown()
        CropAnalyzer.close()
    }
}
