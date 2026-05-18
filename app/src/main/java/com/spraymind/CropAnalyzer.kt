package com.spraymind

import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// Explicitly tells Gemma 4 NOT to enter its thinking/reasoning mode.
// On old phones this alone cuts latency by 60-80% by skipping chain-of-thought tokens.
private const val SYSTEM_INSTRUCTION =
    "You are a fast crop pest scanner on a low-power phone. " +
    "Respond INSTANTLY with NO thinking, NO reasoning, NO explanation. " +
    "Output ONLY the 3 required lines in the exact format. Nothing else."

// Keep the user turn minimal — no extra context the model might reason over.
private const val DETECTION_PROMPT =
    "Analyze this crop image. No thinking. Reply ONLY in this exact format:\n" +
    "SEVERITY: [NONE/LOW/MEDIUM/HIGH]\n" +
    "PESTS: [brief description or None]\n" +
    "ACTION: [one sentence recommendation]\n\n" +
    "NONE=healthy, LOW=few pests, MEDIUM=moderate, HIGH=severe."

// Model pushed via: adb push gemma-4-E2B-it.litertlm /data/local/tmp/
private const val MODEL_PATH = "/data/local/tmp/gemma-4-E2B-it.litertlm"

// LiteRT caches compiled GPU shaders here — subsequent launches skip recompilation.
private const val SHADER_CACHE = "/data/local/tmp/litert-cache/"

object CropAnalyzer {

    val modelSlug: String = "gemma-4-E2B-it"
    private var engine: Engine? = null

    // Reuse this config every frame — creating it is cheap, inference is the bottleneck.
    private val conversationConfig = ConversationConfig(
        systemInstruction = Contents.of(Content.Text(SYSTEM_INSTRUCTION)),
        // topK=1 = greedy decoding: always pick the single most-likely token.
        // Fastest possible sampling — no temperature-based randomness overhead.
        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.1)
    )

    suspend fun downloadModel() {
        val file = File(MODEL_PATH)
        if (!file.exists()) {
            error(
                "Model not found at $MODEL_PATH — run:\n" +
                "adb push gemma-4-E2B-it.litertlm /data/local/tmp/"
            )
        }
        Log.d("SprayMind", "Model found: ${file.length() / 1_000_000} MB")
    }

    suspend fun initializeModel() {
        if (engine != null) return
        engine = buildEngine()
        Log.d("SprayMind", "LiteRT engine ready")
    }

    private fun buildEngine(): Engine {
        // Three-tier waterfall — each tier handles a distinct real-world failure class:
        //   GPU+GPU  : ideal path; fastest and most battery-efficient.
        //   GPU+CPU  : fixes devices with buggy OpenCL/Vulkan vision-encoder drivers
        //              while keeping LLM token decoding on the GPU.
        //   CPU+CPU  : guaranteed fallback; works on every API 24+ device.
        val tiers = listOf(
            Backend.GPU() to Backend.GPU(),
            Backend.GPU() to Backend.CPU(),
            Backend.CPU() to Backend.CPU()
        )
        var lastException: Exception? = null
        for ((backend, visionBackend) in tiers) {
            try {
                return Engine(
                    EngineConfig(
                        modelPath     = MODEL_PATH,
                        backend       = backend,
                        visionBackend = visionBackend,
                        cacheDir      = SHADER_CACHE,
                        // 8 192-token KV cache — large enough for multi-turn advisor thinking
                        // chains (≥4 000 tokens) while staying within RAM budget on 4 GB devices.
                        maxNumTokens  = 8_192
                    )
                ).also {
                    it.initialize()
                    Log.d("SprayMind", "Engine ready — backend=${backend::class.simpleName}, vision=${visionBackend::class.simpleName}")
                }
            } catch (e: Exception) {
                Log.w("SprayMind", "Engine init failed (${backend::class.simpleName}+${visionBackend::class.simpleName}): ${e.message}")
                lastException = e
            }
        }
        throw lastException ?: error("All engine init tiers exhausted")
    }

    // Runs a single dummy inference immediately after initializeModel() so the JIT
    // compiler, GPU command queue, and KV-cache allocator are all warm before the
    // first real farm frame. Cancelled after the first token — just enough to touch
    // every code path that's slow on a cold start.
    suspend fun warmup(cacheDir: File) {
        val eng = engine ?: return
        val tmp = File(cacheDir, "litert_warmup.jpg")
        try {
            // 350×350 grey bitmap — MINIMAL budget resolution, fastest decode path.
            val bmp = Bitmap.createBitmap(350, 350, Bitmap.Config.RGB_565)
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bmp.recycle()

            eng.createConversation(conversationConfig).use { conv ->
                try {
                    conv.sendMessageAsync(
                        Contents.of(Content.ImageFile(tmp.absolutePath), Content.Text("Reply: OK"))
                    ).collect { conv.cancelProcess() }  // cancel on first token; pipeline is now warm
                } catch (_: Exception) { /* cancelProcess() always throws — expected */ }
            }
            Log.d("SprayMind", "Warmup complete")
        } catch (e: Exception) {
            Log.w("SprayMind", "Warmup failed (non-fatal): ${e.message}")
        } finally {
            tmp.delete()
        }
    }

    // budget is passed for logging; the actual token count is determined by the pixel
    // dimensions of the JPEG written by encodeAndHash in MainViewModel.
    // When the LiteRT-LM API exposes a dedicated tokenBudget field on EngineConfig or
    // ConversationConfig, add it here alongside the pixel-size control.
    suspend fun analyzeFrame(imagePath: String, budget: ImageTokenBudget): DetectionResult {
        val eng = engine ?: error("Call initializeModel() first")
        val accumulated = StringBuilder()
        Log.d("SprayMind", "analyzeFrame: budget=${budget.name} (${budget.tokens} tokens, ${budget.targetPx}px)")

        eng.createConversation(conversationConfig).use { conversation ->
            try {
                conversation.sendMessageAsync(
                    Contents.of(Content.ImageFile(imagePath), Content.Text(DETECTION_PROMPT))
                ).collect { message ->
                    val chunk = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                    accumulated.append(chunk)
                    // Cancel only once any <think>…</think> block is closed AND all 3 fields
                    // are parseable — cancelling mid-thought truncates chain-of-thought.
                    if (!hasUnclosedThinkBlock(accumulated.toString()) &&
                        isStructuredResponseComplete(accumulated.toString())) {
                        conversation.cancelProcess()
                    }
                }
            } catch (e: Exception) {
                // cancelProcess() terminates the flow with an exception — that's expected.
                // Only re-throw if we never received any data.
                if (accumulated.isEmpty()) throw e
                Log.d("SprayMind", "Stream ended after early parse (${accumulated.length} chars)")
            }
        }

        val (_, cleanResponse) = parseThinkingAndResponse(accumulated.toString())
        return parseResult(cleanResponse)
    }

    // Splits raw model output into (thinkingText, cleanResponse).
    // thinkingText is everything inside <think>…</think> (may be empty).
    // cleanResponse is the raw text with all thinking blocks removed.
    private fun parseThinkingAndResponse(raw: String): Pair<String, String> {
        val thinkRegex = Regex("""<think>(.*?)</think>""", RegexOption.DOT_MATCHES_ALL)
        val thinking = thinkRegex.findAll(raw).joinToString("\n\n") { it.groupValues[1].trim() }
        val clean = thinkRegex.replace(raw, "").trim()
        return thinking to clean
    }

    // Returns true when a <think> tag has been opened but its </think> closing tag
    // has not yet arrived. Used to prevent early-cancel mid-thought.
    private fun hasUnclosedThinkBlock(text: String): Boolean {
        val openCount  = Regex("<think>").findAll(text).count()
        val closeCount = Regex("</think>").findAll(text).count()
        return openCount > closeCount
    }

    private fun isStructuredResponseComplete(text: String): Boolean {
        val lines = text.lines()
        return lines.any { it.startsWith("SEVERITY:") && it.substringAfter("SEVERITY:").trim().isNotEmpty() } &&
               lines.any { it.startsWith("PESTS:") && it.substringAfter("PESTS:").trim().isNotEmpty() } &&
               lines.any { it.startsWith("ACTION:") && it.substringAfter("ACTION:").trim().isNotEmpty() }
    }

    // ─── Multi-turn advisor loop ──────────────────────────────────────────────
    //
    // Drives a Gemma 4 tool-calling conversation on behalf of SessionAdvisor.
    // Uses the already-loaded engine — no second model load needed.
    //
    // The loop runs until either:
    //   (a) the model produces a response with no tool_call block (done), or
    //   (b) maxTurns is reached (safety ceiling).
    //
    // Each tool call is detected by scanning the model's text output for a
    // <tool_call> block. The result is sent back as a <tool_response> message
    // in the next user turn — a pattern Gemma 4 understands from training.
    //
    suspend fun runAdvisorLoop(
        systemPrompt: String,
        initialMessage: String,
        maxTurns: Int = 10,
        onThinking: ((String) -> Unit)? = null,
        toolExecutor: suspend (toolName: String, argsJson: String) -> String
    ): String {
        val eng = engine ?: error("Call initializeModel() first")

        val advisorConfig = ConversationConfig(
            systemInstruction = Contents.of(Content.Text(systemPrompt)),
            // Greedy decoding — tool call JSON must be exact; no sampling randomness.
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.1)
        )

        return eng.createConversation(advisorConfig).use { conv ->
            var nextMessage = initialMessage
            var lastResponse = ""
            var turns = 0

            while (turns < maxTurns) {
                val accumulated = StringBuilder()
                try {
                    conv.sendMessageAsync(Contents.of(Content.Text(nextMessage)))
                        .collect { msg ->
                            val chunk = msg.contents.contents
                                .filterIsInstance<Content.Text>()
                                .joinToString("") { it.text }
                            accumulated.append(chunk)
                        }
                } catch (e: Exception) {
                    if (accumulated.isEmpty()) throw e
                }

                lastResponse = accumulated.toString()
                Log.d("SprayMind.Advisor", "Model turn ${turns + 1}: ${lastResponse.take(200)}")

                val (thinkText, cleanResponse) = parseThinkingAndResponse(lastResponse)
                if (thinkText.isNotBlank()) onThinking?.invoke(thinkText)
                lastResponse = cleanResponse

                val (toolName, argsJson) = parseToolCall(lastResponse) ?: break

                val result = toolExecutor(toolName, argsJson)
                // Format the result so Gemma 4 recognises it as a tool response.
                nextMessage = "<tool_response>\n{\"name\": \"$toolName\", \"content\": $result}\n</tool_response>"
                turns++
            }

            lastResponse
        }
    }

    // Handles both Gemma 4 output formats:
    //   <tool_call>{"name": ..., "arguments": {...}}</tool_call>
    //   ```tool_call\n{"name": ..., "arguments": {...}}\n```
    private fun parseToolCall(text: String): Pair<String, String>? {
        val patterns = listOf(
            Regex("""<tool_call>\s*(\{.*?\})\s*</tool_call>""",        RegexOption.DOT_MATCHES_ALL),
            Regex("""```tool_call\s*\n(\{.*?\})\s*\n?```""",           RegexOption.DOT_MATCHES_ALL),
            Regex("""```json\s*\n(\{"name"\s*:.*?"arguments".*?\})\s*\n?```""", RegexOption.DOT_MATCHES_ALL)
        )
        for (pattern in patterns) {
            val json = pattern.find(text)?.groupValues?.getOrNull(1) ?: continue
            return runCatching {
                val obj  = JSONObject(json)
                val name = obj.getString("name")
                val args = obj.optJSONObject("arguments")?.toString() ?: "{}"
                name to args
            }.getOrNull() ?: continue
        }
        return null
    }

    fun close() {
        engine?.close()
        engine = null
    }

    private fun parseResult(raw: String): DetectionResult {
        val lines = raw.lines()

        val severity = lines
            .firstOrNull { it.startsWith("SEVERITY:") }
            ?.substringAfter("SEVERITY:")?.trim()
            ?.let { text ->
                when {
                    text.contains("HIGH", ignoreCase = true) -> Severity.HIGH
                    text.contains("MEDIUM", ignoreCase = true) -> Severity.MEDIUM
                    text.contains("LOW", ignoreCase = true) -> Severity.LOW
                    else -> Severity.NONE
                }
            } ?: Severity.NONE

        val pests = lines.firstOrNull { it.startsWith("PESTS:") }
            ?.substringAfter("PESTS:")?.trim() ?: "Unknown"

        val action = lines.firstOrNull { it.startsWith("ACTION:") }
            ?.substringAfter("ACTION:")?.trim() ?: "Continue monitoring"

        return DetectionResult(
            severity = severity,
            pests = pests,
            action = action,
            pressureBar = severity.toPressureBar()
        )
    }
}
