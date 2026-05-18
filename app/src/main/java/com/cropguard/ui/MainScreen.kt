package com.cropguard.ui

import android.app.Activity
import android.util.Log
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.cropguard.AppState
import com.cropguard.DetectionResult
import com.cropguard.MainViewModel
import com.cropguard.Severity
import com.cropguard.db.YardProfile
import com.cropguard.db.YardSession
import com.cropguard.label
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Background = Color(0xFF080E08)
private val GreenColor  = Color(0xFF4CAF50)
private val PanelColor  = Color(0xFF0F1A0F)
private val DimGreen    = Color(0xFF1C2E1C)

fun Severity.uiColor(): Color = when (this) {
    Severity.NONE   -> Color(0xFF4CAF50)
    Severity.LOW    -> Color(0xFFFFEB3B)
    Severity.MEDIUM -> Color(0xFFFF9800)
    Severity.HIGH   -> Color(0xFFF44336)
}

private fun severityFromName(name: String): Severity =
    runCatching { Severity.valueOf(name) }.getOrDefault(Severity.NONE)

private fun formatDate(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))

// ─── Root router ───────────────────────────────────────────────────────────────

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center
    ) {
        when (val s = state) {
            is AppState.Downloading  -> BootScreen("Downloading AI model", "~1.5 GB — internet required once\nFully offline after this")
            is AppState.Initializing -> BootScreen("Loading AI model", "Almost ready...")
            is AppState.YardSelect   -> YardSelectScreen(
                profiles  = s.profiles,
                onSelect  = viewModel::selectExistingYard,
                onNewYard = viewModel::createNewYard
            )
            is AppState.YardSetup -> YardSetupScreen(
                existing  = s.existing,
                onConfirm = { name, w, h, lat, lon ->
                    viewModel.confirmYardSetup(name, w, h, lat, lon, s.existing)
                }
            )
            is AppState.Live     -> LiveDashboard(viewModel)
            is AppState.Finished -> FinishedScreen(viewModel)
            is AppState.Error    -> ErrorScreen(message = s.message, onRetry = viewModel::retry)
        }
    }
}

// ─── Boot ─────────────────────────────────────────────────────────────────────

@Composable
private fun BootScreen(title: String, subtitle: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.padding(40.dp)
    ) {
        Text("CropGuard", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = GreenColor)
        CircularProgressIndicator(color = GreenColor, modifier = Modifier.size(48.dp))
        Text(title,    color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
        Text(subtitle, color = Color.Gray,  fontSize = 13.sp, textAlign = TextAlign.Center)
        LinearProgressIndicator(
            modifier   = Modifier.width(240.dp),
            color      = GreenColor,
            trackColor = Color(0xFF1A2E1A)
        )
    }
}

// ─── Yard selection ────────────────────────────────────────────────────────────

@Composable
private fun YardSelectScreen(
    profiles: List<YardProfile>,
    onSelect: (YardProfile) -> Unit,
    onNewYard: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(40.dp))

        Text("CropGuard", color = GreenColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Your Fields",  color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)

        Spacer(Modifier.height(20.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(profiles) { _, profile ->
                YardCard(profile = profile, onClick = { onSelect(profile) })
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        Button(
            onClick  = onNewYard,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DimGreen)
        ) {
            Text("+ New Field", color = GreenColor, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun YardCard(profile: YardProfile, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                profile.name,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${profile.widthMeters} × ${profile.heightMeters} m²",
                color = GreenColor,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            "Last visit: ${formatDate(profile.lastVisitAt)}",
            color = Color.Gray,
            fontSize = 12.sp
        )
        Text(
            "${profile.totalSquareMeters} square meters",
            color = Color(0xFF5A7A5A),
            fontSize = 11.sp
        )
    }
}

// ─── Yard setup ────────────────────────────────────────────────────────────────

@Composable
private fun YardSetupScreen(
    existing: YardProfile?,
    onConfirm: (name: String, width: Int, height: Int, lat: Double?, lon: Double?) -> Unit
) {
    // Pre-fill from the saved profile so the farmer doesn't re-type every visit.
    var nameText   by remember { mutableStateOf(existing?.name ?: "") }
    var widthText  by remember { mutableStateOf(if (existing != null) "${existing.widthMeters}"  else "") }
    var heightText by remember { mutableStateOf(if (existing != null) "${existing.heightMeters}" else "") }
    var latText    by remember { mutableStateOf(existing?.latitudeDeg?.toString() ?: "") }
    var lonText    by remember { mutableStateOf(existing?.longitudeDeg?.toString() ?: "") }

    val w   = widthText.toIntOrNull() ?: 0
    val h   = heightText.toIntOrNull() ?: 0
    val lat = latText.toDoubleOrNull()
    val lon = lonText.toDoubleOrNull()
    val canStart = nameText.isNotBlank() && w > 0 && h > 0

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor     = Color.White,
        unfocusedTextColor   = Color.White,
        focusedBorderColor   = GreenColor,
        unfocusedBorderColor = Color(0xFF3A3A3A),
        focusedLabelColor    = GreenColor,
        unfocusedLabelColor  = Color.Gray,
        cursorColor          = GreenColor,
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(horizontal = 36.dp)
    ) {
        Text("CropGuard", color = GreenColor, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(
            if (existing != null) "Edit Field" else "New Field",
            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium
        )
        Text(
            "Name your field and enter its dimensions so the app\nguides you square meter by square meter.",
            color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(4.dp))

        OutlinedTextField(
            value         = nameText,
            onValueChange = { nameText = it.take(40) },
            label         = { Text("Field name (e.g. North Tomatoes)") },
            singleLine    = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            colors        = fieldColors,
            modifier      = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value         = widthText,
            onValueChange = { widthText = it.filter { c -> c.isDigit() }.take(4) },
            label         = { Text("Width (meters)") },
            singleLine    = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction    = ImeAction.Next
            ),
            colors   = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value         = heightText,
            onValueChange = { heightText = it.filter { c -> c.isDigit() }.take(4) },
            label         = { Text("Height (meters)") },
            singleLine    = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction    = ImeAction.Next
            ),
            colors   = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )

        // Optional GPS — enables weather-aware re-inspection scheduling.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value         = latText,
                onValueChange = { latText = it.filter { c -> c.isDigit() || c == '.' || c == '-' }.take(10) },
                label         = { Text("Lat (opt.)") },
                singleLine    = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction    = ImeAction.Next
                ),
                colors   = fieldColors,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value         = lonText,
                onValueChange = { lonText = it.filter { c -> c.isDigit() || c == '.' || c == '-' }.take(10) },
                label         = { Text("Lon (opt.)") },
                singleLine    = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction    = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { if (canStart) onConfirm(nameText.trim(), w, h, lat, lon) }
                ),
                colors   = fieldColors,
                modifier = Modifier.weight(1f)
            )
        }

        if (lat != null && lon != null) {
            Text(
                "Weather-aware scheduling enabled",
                color = GreenColor, fontSize = 12.sp
            )
        }

        if (w > 0 && h > 0) {
            Text(
                "${w * h} square meters to scan",
                color = GreenColor, fontSize = 14.sp, fontWeight = FontWeight.Medium
            )
        }

        Button(
            onClick  = { onConfirm(nameText.trim(), w, h, lat, lon) },
            enabled  = canStart,
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.buttonColors(containerColor = GreenColor)
        ) {
            Text("Start Scanning", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

// ─── Live scanning dashboard ───────────────────────────────────────────────────

@Composable
private fun LiveDashboard(viewModel: MainViewModel) {
    val context       = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentResult    by viewModel.currentResult.collectAsState()
    val isAnalyzing      by viewModel.isAnalyzing.collectAsState()
    val frameCount       by viewModel.frameCount.collectAsState()
    val skippedFrames    by viewModel.skippedFrameCount.collectAsState()
    val scanHistory      by viewModel.scanHistory.collectAsState()
    val yardWidth        by viewModel.yardWidth.collectAsState()
    val yardHeight       by viewModel.yardHeight.collectAsState()

    // Keep screen on while scanning — sleeps would kill the camera preview mid-session.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    DisposableEffect(Unit) {
        viewModel.startAnalysis(context.cacheDir)
        onDispose { viewModel.stopAnalysis() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(0.58f)) {
            CameraPreview(lifecycleOwner = lifecycleOwner, imageCapture = viewModel.imageCapture)
            ScanOverlay(
                isAnalyzing   = isAnalyzing,
                frameCount    = frameCount,
                skippedFrames = skippedFrames
            )
        }

        YardGridPanel(
            yardWidth   = yardWidth,
            yardHeight  = yardHeight,
            result      = currentResult,
            scanHistory = scanHistory,
            isAnalyzing = isAnalyzing,
            onFinish    = viewModel::finish,
            modifier    = Modifier
                .fillMaxWidth()
                .weight(0.42f)
                .background(PanelColor)
        )
    }
}

@Composable
private fun CameraPreview(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    imageCapture: androidx.camera.core.ImageCapture
) {
    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).also { previewView ->
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture
                        )
                    } catch (e: Exception) {
                        Log.e("CropGuard", "Camera bind failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun ScanOverlay(isAnalyzing: Boolean, frameCount: Int, skippedFrames: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "borderPulse"
    )
    val scanLineY by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "scanLine"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        if (isAnalyzing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(2.dp, GreenColor.copy(alpha = pulseAlpha), RectangleShape)
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val bracketPx   = 36.dp.toPx()
            val strokePx    = 3.dp.toPx()
            val inset       = 18.dp.toPx()
            val bracketColor = GreenColor.copy(alpha = if (isAnalyzing) pulseAlpha else 0.5f)

            drawLine(bracketColor, Offset(inset, inset), Offset(inset + bracketPx, inset), strokePx, StrokeCap.Round)
            drawLine(bracketColor, Offset(inset, inset), Offset(inset, inset + bracketPx), strokePx, StrokeCap.Round)
            drawLine(bracketColor, Offset(size.width - inset, inset), Offset(size.width - inset - bracketPx, inset), strokePx, StrokeCap.Round)
            drawLine(bracketColor, Offset(size.width - inset, inset), Offset(size.width - inset, inset + bracketPx), strokePx, StrokeCap.Round)
            drawLine(bracketColor, Offset(inset, size.height - inset), Offset(inset + bracketPx, size.height - inset), strokePx, StrokeCap.Round)
            drawLine(bracketColor, Offset(inset, size.height - inset), Offset(inset, size.height - inset - bracketPx), strokePx, StrokeCap.Round)
            drawLine(bracketColor, Offset(size.width - inset, size.height - inset), Offset(size.width - inset - bracketPx, size.height - inset), strokePx, StrokeCap.Round)
            drawLine(bracketColor, Offset(size.width - inset, size.height - inset), Offset(size.width - inset, size.height - inset - bracketPx), strokePx, StrokeCap.Round)

            if (isAnalyzing) {
                val y = size.height * scanLineY
                if (y > 0f) {
                    val trailH = minOf(80.dp.toPx(), y)
                    drawRect(
                        brush     = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, GreenColor.copy(alpha = 0.12f)),
                            startY = y - trailH, endY = y
                        ),
                        topLeft = Offset(0f, y - trailH),
                        size    = Size(size.width, trailH)
                    )
                }
                drawLine(
                    brush       = Brush.horizontalGradient(
                        listOf(Color.Transparent, GreenColor, GreenColor, Color.Transparent)
                    ),
                    start       = Offset(0f, y),
                    end         = Offset(size.width, y),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }

        // Scan counter — top right
        Text(
            text = when {
                frameCount == 0  -> "Starting..."
                skippedFrames > 0 -> "Scan #$frameCount  |  $skippedFrames identical skipped"
                else             -> "Scan #$frameCount"
            },
            color    = Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )

        // Status — bottom left
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text  = if (isAnalyzing) "Analyzing..." else "Monitoring",
                color = if (isAnalyzing) GreenColor else Color.LightGray,
                fontSize = 11.sp
            )
        }
    }
}

// ─── Yard grid panel ───────────────────────────────────────────────────────────

@Composable
private fun YardGridPanel(
    yardWidth: Int, yardHeight: Int,
    result: DetectionResult?,
    scanHistory: List<DetectionResult>,
    isAnalyzing: Boolean,
    onFinish: () -> Unit,
    modifier: Modifier
) {
    val totalCells    = yardWidth * yardHeight
    val scanned       = minOf(scanHistory.size, totalCells)
    val severity      = result?.severity ?: Severity.NONE
    val severityColor = severity.uiColor()

    Column(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SeverityBadge(severity, severityColor)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isAnalyzing) CircularProgressIndicator(
                    modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = GreenColor
                )
                Text(
                    "%.1f BAR".format(result?.pressureBar ?: 0f),
                    color = severityColor, fontSize = 17.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
            }
            Text("$scanned / $totalCells m²", color = Color.Gray, fontSize = 12.sp)
        }

        YardGrid(
            yardWidth   = yardWidth,
            yardHeight  = yardHeight,
            scanHistory = scanHistory,
            isAnalyzing = isAnalyzing,
            modifier    = Modifier.fillMaxWidth().weight(1f)
        )

        Button(
            onClick  = onFinish,
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.buttonColors(containerColor = DimGreen)
        ) {
            Text("Finish Session", color = GreenColor, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun YardGrid(
    yardWidth: Int, yardHeight: Int,
    scanHistory: List<DetectionResult>,
    isAnalyzing: Boolean,
    modifier: Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "grid")
    val cellPulse by infiniteTransition.animateFloat(
        initialValue = 0.35f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "cellPulse"
    )

    val totalCells   = yardWidth * yardHeight
    val scannedCount = minOf(scanHistory.size, totalCells)

    Canvas(modifier = modifier) {
        if (yardWidth <= 0 || yardHeight <= 0) return@Canvas
        val gapPx = 2.dp.toPx()
        val cellW = (size.width  - gapPx * (yardWidth  - 1)) / yardWidth
        val cellH = (size.height - gapPx * (yardHeight - 1)) / yardHeight

        for (row in 0 until yardHeight) {
            for (col in 0 until yardWidth) {
                val idx = row * yardWidth + col
                val cellColor = when {
                    idx < scannedCount                              -> scanHistory[idx].severity.uiColor().copy(alpha = 0.85f)
                    idx == scannedCount && scannedCount < totalCells -> GreenColor.copy(alpha = cellPulse)
                    else                                             -> Color(0xFF1A2E1A)
                }
                drawRect(
                    color   = cellColor,
                    topLeft = Offset(col * (cellW + gapPx), row * (cellH + gapPx)),
                    size    = Size(cellW, cellH)
                )
            }
        }
    }
}

// ─── Shared widgets ────────────────────────────────────────────────────────────

@Composable
private fun SeverityBadge(severity: Severity, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 5.dp)
    ) {
        Text(
            text = severity.label(),
            color = color, fontWeight = FontWeight.Bold,
            fontSize = 13.sp, letterSpacing = 1.sp
        )
    }
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray,  fontSize = 13.sp)
        Text(value, color = valueColor,  fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ─── Finished / analytics ──────────────────────────────────────────────────────

@Composable
private fun FinishedScreen(viewModel: MainViewModel) {
    val history         by viewModel.scanHistory.collectAsState()
    val previousSession by viewModel.previousSession.collectAsState()
    val activeProfile   by viewModel.activeProfile.collectAsState()
    val advisorThinking by viewModel.advisorThinking.collectAsState()

    var notes by remember { mutableStateOf("") }

    val peakSeverity = history.maxByOrNull { it.severity.ordinal }?.severity ?: Severity.NONE
    val avgPressure  = if (history.isEmpty()) 0f else history.map { it.pressureBar }.average().toFloat()

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor     = Color.White,
        unfocusedTextColor   = Color.White,
        focusedBorderColor   = GreenColor,
        unfocusedBorderColor = Color(0xFF3A3A3A),
        focusedLabelColor    = GreenColor,
        unfocusedLabelColor  = Color.Gray,
        cursorColor          = GreenColor,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(32.dp))

        Text("CropGuard",        color = GreenColor,  fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Session Complete",  color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)

        activeProfile?.let {
            Text(it.name, color = Color.Gray, fontSize = 13.sp)
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Current session summary ──────────────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PanelColor, RoundedCornerShape(8.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("This Session", color = GreenColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    StatRow("Total scans",   "${history.size}")
                    StatRow("Peak severity", peakSeverity.label(), peakSeverity.uiColor())
                    StatRow("Avg pressure",  "%.1f BAR".format(avgPressure))
                }
            }

            // ── Before / after comparison ────────────────────────────────────
            if (previousSession != null && history.isNotEmpty()) {
                item {
                    ComparisonCard(
                        previous       = previousSession!!,
                        currentPeak    = peakSeverity,
                        currentAvgBar  = avgPressure
                    )
                }
            }

            // ── Per-meter scan history ───────────────────────────────────────
            if (history.isNotEmpty()) {
                item {
                    Text(
                        "Scan Log",
                        color = Color.Gray, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                itemsIndexed(history) { index, result ->
                    ScanHistoryItem(squareMeter = index + 1, result = result)
                }
            } else {
                item {
                    Text(
                        "No scans recorded in this session.",
                        color = Color.Gray, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // ── Advisor reasoning ────────────────────────────────────────────
            if (advisorThinking.isNotBlank()) {
                item { ExpandableReasoningCard(thinkingText = advisorThinking) }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }

        // ── Notes ────────────────────────────────────────────────────────────
        OutlinedTextField(
            value         = notes,
            onValueChange = { notes = it },
            label         = { Text("Session notes (optional)") },
            placeholder   = { Text("e.g. Applied neem oil 2 L/m², after rain", color = Color(0xFF4A4A4A)) },
            maxLines      = 3,
            colors        = fieldColors,
            modifier      = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        Button(
            onClick  = { viewModel.newSession(notes) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GreenColor)
        ) {
            Text("Save & New Session", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ComparisonCard(
    previous: YardSession,
    currentPeak: Severity,
    currentAvgBar: Float
) {
    val prevPeak    = severityFromName(previous.peakSeverity)
    val prevAvg     = previous.avgPressureBar
    val delta       = currentAvgBar - prevAvg
    val deltaText   = if (delta >= 0f) "+%.1f BAR".format(delta) else "%.1f BAR".format(delta)
    // Pressure increase = worse (red), decrease = better (green — pesticide worked)
    val deltaColor  = if (delta > 0f) Color(0xFFF44336) else GreenColor
    val trendLabel  = if (delta > 0f) "pressure up" else if (delta < 0f) "pressure down" else "no change"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A1A1A), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF1E3A2E), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("vs. Previous Visit", color = GreenColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(formatDate(previous.startedAt), color = Color.Gray, fontSize = 11.sp)
        }

        // Severity comparison row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Severity", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.width(70.dp))
            SeverityBadge(prevPeak, prevPeak.uiColor())
            Text("→", color = Color.Gray, fontSize = 14.sp)
            SeverityBadge(currentPeak, currentPeak.uiColor())
        }

        // Pressure comparison row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Pressure", color = Color.Gray, fontSize = 12.sp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("%.1f".format(prevAvg),     color = Color.Gray,  fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Text("→",                        color = Color.Gray,  fontSize = 13.sp)
                Text("%.1f BAR".format(currentAvgBar), color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                Text(
                    "$deltaText  ($trendLabel)",
                    color = deltaColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (previous.notes.isNotBlank()) {
            Text(
                "Previous notes: ${previous.notes}",
                color = Color(0xFF5A7A5A), fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ScanHistoryItem(squareMeter: Int, result: DetectionResult) {
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(result.analyzedAt))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelColor, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Square Meter #$squareMeter", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeverityBadge(result.severity, result.severity.uiColor())
                Text(timeStr, color = Color.Gray, fontSize = 11.sp)
            }
        }
        Text(result.pests,  color = Color.LightGray,            fontSize = 12.sp, maxLines = 1)
        Text(result.action, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 2)
    }
}

// ─── AI Reasoning card ─────────────────────────────────────────────────────────
//
// Shows the advisor's <think> tokens as an expandable disclosure widget.
// Collapsed by default so non-technical farmers don't see the raw model output
// unless they tap to explore. Blue accent keeps it visually distinct from the
// green crop-health UI palette.

private val ThinkBlue = Color(0xFF4FC3F7)

@Composable
private fun ExpandableReasoningCard(thinkingText: String) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A1520), RoundedCornerShape(8.dp))
            .border(1.dp, ThinkBlue.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "AI Reasoning",
                color = ThinkBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
            Text(
                if (expanded) "▲ hide" else "▼ show",
                color = ThinkBlue.copy(alpha = 0.7f), fontSize = 11.sp
            )
        }

        AnimatedVisibility(visible = expanded) {
            Text(
                text = thinkingText,
                color = Color(0xFFB0C8D8),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp
            )
        }
    }
}

// ─── Error ─────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(40.dp)
    ) {
        Text("Something went wrong", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(message, color = Color(0xFFFF6B6B), fontSize = 13.sp, textAlign = TextAlign.Center)
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = GreenColor)) {
            Text("Retry", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}
