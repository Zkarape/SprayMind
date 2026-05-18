package com.cropguard

import android.content.Context
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.cropguard.db.CropGuardDatabase
import com.cropguard.db.ScheduledNotification
import com.cropguard.db.YardProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

// ─── SessionAdvisor ───────────────────────────────────────────────────────────
//
// After each completed field scan, this object drives a multi-turn Gemma 4
// conversation to decide WHEN the farmer should next inspect specific cells.
//
// Instead of a naive 7-day cron, the model reasons over:
//   • Per-cell severity history from previous sessions
//   • Treatment applied (farmer's session notes)
//   • 7-day weather forecast (rain washes off organic sprays)
//
// It then calls schedule_notification / dismiss_notifications as tools to
// enqueue WorkManager jobs that fire as Android push notifications.
// An agronomist doing this manually would take 20 minutes per farm.
// The model does it in < 30 seconds per session — fully offline.
//
object SessionAdvisor {

    private const val TAG = "CropGuard.Advisor"
    private const val MAX_TOOL_TURNS  = 10   // safety ceiling on tool-call rounds
    private const val MAX_NOTIF_COUNT = 3    // notification fatigue limit per session

    // ─── Gemma 4 tool definitions ────────────────────────────────────────────
    //
    // Embedded as JSON in the system prompt — Gemma 4 is trained to read this
    // format and produce <tool_call> blocks in its responses.
    //
    private val TOOL_JSON = """
[
  {
    "name": "get_cell_history",
    "description": "Retrieve recent inspection records for a specific field cell across past sessions.",
    "parameters": {
      "type": "object",
      "properties": {
        "fieldId":       { "type": "string",  "description": "Yard profile ID as a string" },
        "cellIndex":     { "type": "integer", "description": "Zero-based linear cell index: row * yardWidth + col" },
        "lastNSessions": { "type": "integer", "description": "Number of past sessions to include (default 3, max 5)" }
      },
      "required": ["fieldId", "cellIndex"]
    }
  },
  {
    "name": "get_weather_forecast",
    "description": "Fetch a 7-day weather forecast for the field location. Call only when the farmer mentioned applying a spray treatment — rain within 24h of application date washes it off.",
    "parameters": {
      "type": "object",
      "properties": {
        "latitude":  { "type": "number", "description": "Field latitude in decimal degrees" },
        "longitude": { "type": "number", "description": "Field longitude in decimal degrees" }
      },
      "required": ["latitude", "longitude"]
    }
  },
  {
    "name": "schedule_notification",
    "description": "Schedule a push notification to remind the farmer to re-inspect a cell.",
    "parameters": {
      "type": "object",
      "properties": {
        "title":           { "type": "string", "description": "Short title (< 60 chars)" },
        "body":            { "type": "string", "description": "Notification body explaining what to look for (< 200 chars, plain farmer language)" },
        "scheduledForIso": { "type": "string", "description": "ISO-8601 local datetime: yyyy-MM-dd'T'HH:mm:ss — use 08:00:00 for morning reminders" },
        "urgency":         { "type": "string", "enum": ["low", "medium", "high"] },
        "cellKey":         { "type": "string", "description": "Cell identifier 'row:col' or 'yard' for yard-wide" }
      },
      "required": ["title", "body", "scheduledForIso", "urgency"]
    }
  },
  {
    "name": "dismiss_notifications",
    "description": "Cancel pending notifications for cells confirmed healthy this session. Prevents false alarms.",
    "parameters": {
      "type": "object",
      "properties": {
        "cellKeys": { "type": "array", "items": { "type": "string" }, "description": "Array of cell keys whose pending notifications should be cancelled" }
      },
      "required": ["cellKeys"]
    }
  }
]""".trimIndent()

    private val SYSTEM_PROMPT = """
You are an agricultural timing advisor running on a farmer's phone.
After each field inspection you analyze results and schedule smart re-inspection reminders.

You have access to these tools:
$TOOL_JSON

Decision rules (follow exactly):
1. Call get_cell_history BEFORE scheduling any notification — never schedule without history.
2. If session notes mention spraying, call get_weather_forecast to check for rain that would wash it off.
3. Schedule at MOST $MAX_NOTIF_COUNT notifications total — farmers ignore excess alerts.
4. Treatment efficacy windows (schedule re-inspection at window end):
   - Neem oil / neem extract : 5–7 days
   - Copper fungicide        : 10–14 days
   - Pyrethrin / spinosad    : 3–5 days
   - Water rinse / soap spray: 2–3 days
   - No treatment recorded   : HIGH→next day, MEDIUM→3 days, LOW→5 days
5. If forecast shows rain > 5 mm within 24 h of a planned spray day, warn the farmer to wait.
6. Dismiss pending notifications for cells confirmed NONE (healthy) this session.
7. Schedule notifications at 08:00 local time unless urgency is high (use 06:00 for high).
8. Write notification text in plain language — no Latin pest names, no jargon.

Tool call order: get_cell_history → get_weather_forecast (if needed) → schedule/dismiss.
""".trimIndent()

    // ─── Entry point ─────────────────────────────────────────────────────────

    suspend fun runForSession(
        sessionId: Long,
        yardProfile: YardProfile,
        scanHistory: List<DetectionResult>,
        notes: String,
        db: CropGuardDatabase,
        context: Context,
        onThinking: ((String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        if (scanHistory.none { it.severity != Severity.NONE }) {
            Log.d(TAG, "All cells healthy — no advisor actions needed")
            return@withContext
        }
        Log.d(TAG, "Advisor starting for session $sessionId on '${yardProfile.name}'")

        val scheduled = mutableListOf<Long>()   // DB IDs of scheduled notifications

        try {
            CropAnalyzer.runAdvisorLoop(
                systemPrompt   = SYSTEM_PROMPT,
                initialMessage = buildSessionSummary(yardProfile, scanHistory, notes),
                maxTurns       = MAX_TOOL_TURNS,
                onThinking     = onThinking
            ) { toolName, argsJson ->
                executeTool(toolName, argsJson, yardProfile, db, context, scheduled)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Advisor loop error: ${e.message}", e)
        }

        Log.d(TAG, "Advisor complete — ${scheduled.size} notification(s) scheduled")
    }

    // ─── Session summary (first message to the model) ─────────────────────────

    private fun buildSessionSummary(
        profile: YardProfile,
        history: List<DetectionResult>,
        notes: String
    ): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val problematic = history.mapIndexed { idx, r -> idx to r }
            .filter { (_, r) -> r.severity != Severity.NONE }

        return buildString {
            appendLine("Field inspection completed.")
            appendLine("Field name : ${profile.name}")
            appendLine("Field ID   : ${profile.id}")
            appendLine("Dimensions : ${profile.widthMeters} × ${profile.heightMeters} m (${profile.totalSquareMeters} cells)")
            appendLine("Scan date  : $now")
            if (profile.hasLocation) {
                appendLine("GPS        : lat=${profile.latitudeDeg}, lon=${profile.longitudeDeg}")
            } else {
                appendLine("GPS        : not set — skip get_weather_forecast")
            }
            appendLine()
            appendLine("Problem cells this session (${problematic.size} of ${history.size}):")
            if (problematic.isEmpty()) {
                appendLine("  None — all cells returned NONE severity.")
            } else {
                // Cap at 20 cells to keep the prompt short; advisor can call get_cell_history for details.
                problematic.take(20).forEach { (idx, r) ->
                    val row = idx / profile.widthMeters
                    val col = idx % profile.widthMeters
                    appendLine("  cellIndex=$idx (row=$row, col=$col): severity=${r.severity}  pests=${r.pests}  action=${r.action}")
                }
                if (problematic.size > 20) appendLine("  ... and ${problematic.size - 20} more")
            }
            appendLine()
            appendLine("Farmer notes: ${notes.ifBlank { "(none)" }}")
            appendLine()
            appendLine("Analyze the above. Call get_cell_history for cells you plan to schedule.")
            appendLine("Then schedule or dismiss notifications as appropriate.")
        }
    }

    // ─── Tool dispatcher ─────────────────────────────────────────────────────

    private suspend fun executeTool(
        toolName: String,
        argsJson: String,
        yardProfile: YardProfile,
        db: CropGuardDatabase,
        context: Context,
        scheduled: MutableList<Long>
    ): String {
        Log.d(TAG, "Tool call: $toolName  args=$argsJson")
        return try {
            val args = JSONObject(argsJson)
            when (toolName) {
                "get_cell_history"      -> getCellHistory(args, yardProfile, db)
                "get_weather_forecast"  -> getWeatherForecast(args)
                "schedule_notification" -> scheduleNotification(args, yardProfile, db, context, scheduled)
                "dismiss_notifications" -> dismissNotifications(args, yardProfile, db, context)
                else -> error("Unknown tool: $toolName")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tool $toolName failed: ${e.message}")
            JSONObject().put("error", e.message?.take(120)).toString()
        }
    }

    // ─── Tool: get_cell_history ───────────────────────────────────────────────

    private suspend fun getCellHistory(
        args: JSONObject,
        yardProfile: YardProfile,
        db: CropGuardDatabase
    ): String {
        val cellIndex = args.getInt("cellIndex")
        val lastN     = args.optInt("lastNSessions", 3).coerceIn(1, 5)

        // squareMeter in ScanRecord is 1-based (cellIndex + 1)
        val targetSqm = cellIndex + 1
        val sessions  = db.yardSessionDao().getLastN(yardProfile.id, lastN)
        val historyArr = JSONArray()

        sessions.forEach { session ->
            val records = db.scanRecordDao().getForSession(session.id)
                .filter { it.squareMeter == targetSqm }
            records.forEach { rec ->
                historyArr.put(JSONObject().apply {
                    put("date",         formatMs(session.startedAt))
                    put("severity",     rec.severity)
                    put("pests",        rec.pests)
                    put("action",       rec.action)
                    put("sessionNotes", session.notes.ifBlank { "none" })
                })
            }
        }

        return JSONObject().apply {
            put("cellIndex",  cellIndex)
            put("yardName",   yardProfile.name)
            put("yardWidth",  yardProfile.widthMeters)
            put("sessionsChecked", sessions.size)
            put("history",    historyArr)
        }.toString()
    }

    // ─── Tool: get_weather_forecast ───────────────────────────────────────────

    private suspend fun getWeatherForecast(args: JSONObject): String {
        val lat = args.getDouble("latitude")
        val lon = args.getDouble("longitude")
        val forecast = WeatherClient.getForecast(lat, lon)
            ?: return JSONObject().put("error", "Network unavailable — proceed without weather data").toString()

        val daysArr = JSONArray()
        forecast.forEach { day ->
            daysArr.put(JSONObject().apply {
                put("date",           day.date)
                put("precipMm",       day.precipMm)
                put("maxTempC",       day.maxTempC)
                put("rainWarning",    day.hasSignificantRain)
                put("hotAndDry",      day.isHotAndDry)
            })
        }

        return JSONObject().apply {
            put("location", "lat=$lat lon=$lon")
            put("note",     "rainWarning=true means >5mm rain — do not spray on that day; hot+dry favours spider mites")
            put("forecast", daysArr)
        }.toString()
    }

    // ─── Tool: schedule_notification ─────────────────────────────────────────

    private suspend fun scheduleNotification(
        args: JSONObject,
        yardProfile: YardProfile,
        db: CropGuardDatabase,
        context: Context,
        scheduled: MutableList<Long>
    ): String {
        if (scheduled.size >= MAX_NOTIF_COUNT) {
            return JSONObject().apply {
                put("status", "skipped")
                put("reason", "notification limit of $MAX_NOTIF_COUNT reached")
            }.toString()
        }

        val title       = args.getString("title").take(60)
        val body        = args.getString("body").take(200)
        val isoDateTime = args.getString("scheduledForIso")
        val urgency     = args.optString("urgency", "medium")
        val cellKey     = args.optString("cellKey", "yard")

        val scheduledMs = parseIsoToMs(isoDateTime)
            ?: return JSONObject().put("error", "Cannot parse datetime: $isoDateTime").toString()

        val delayMs = (scheduledMs - System.currentTimeMillis()).coerceAtLeast(60_000L)

        // Persist so we can cancel if the cell heals in a future session.
        val record = ScheduledNotification(
            yardProfileId  = yardProfile.id,
            cellKey        = cellKey,
            title          = title,
            body           = body,
            scheduledForMs = scheduledMs,
            urgency        = urgency
        )
        val notifId = db.scheduledNotificationDao().insert(record)

        // WorkManager enqueues a job that survives app death and device reboots.
        val request = OneTimeWorkRequestBuilder<InspectionReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(
                InspectionReminderWorker.KEY_TITLE    to title,
                InspectionReminderWorker.KEY_BODY     to body,
                InspectionReminderWorker.KEY_NOTIF_ID to notifId.toInt()
            ))
            .addTag("cropguard_reminder")
            .build()

        WorkManager.getInstance(context).enqueue(request)
        db.scheduledNotificationDao().updateWorkRequestId(notifId, request.id.toString())
        scheduled.add(notifId)

        val delayH = delayMs / 3_600_000
        Log.d(TAG, "Scheduled '$title' in ~${delayH}h (cellKey=$cellKey, urgency=$urgency)")

        return JSONObject().apply {
            put("status",      "scheduled")
            put("id",          notifId)
            put("delayHours",  delayH)
            put("cellKey",     cellKey)
        }.toString()
    }

    // ─── Tool: dismiss_notifications ─────────────────────────────────────────

    private suspend fun dismissNotifications(
        args: JSONObject,
        yardProfile: YardProfile,
        db: CropGuardDatabase,
        context: Context
    ): String {
        val arr      = args.getJSONArray("cellKeys")
        val cellKeys = (0 until arr.length()).map { arr.getString(it) }

        val pending = db.scheduledNotificationDao().getForYard(yardProfile.id)
            .filter { it.cellKey in cellKeys }

        pending.forEach { notif ->
            if (notif.workRequestId.isNotBlank()) {
                runCatching {
                    WorkManager.getInstance(context)
                        .cancelWorkById(UUID.fromString(notif.workRequestId))
                }
            }
        }
        db.scheduledNotificationDao().dismissByCellKeys(yardProfile.id, cellKeys)

        Log.d(TAG, "Dismissed ${pending.size} pending notification(s) for $cellKeys")

        return JSONObject().apply {
            put("dismissed", pending.size)
            put("cellKeys",  arr)
        }.toString()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun parseIsoToMs(iso: String): Long? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                java.time.ZonedDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (_: Exception) {
                java.time.LocalDateTime.parse(iso)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }
        } else {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(iso)?.time
        }
    }.getOrNull()

    private fun formatMs(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
}
