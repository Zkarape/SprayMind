package com.cropguard

enum class Severity { NONE, LOW, MEDIUM, HIGH }

data class DetectionResult(
    val severity: Severity,
    val pests: String,
    val action: String,
    val pressureBar: Float,
    val analyzedAt: Long = System.currentTimeMillis()
)

fun Severity.toPressureBar(): Float = when (this) {
    Severity.NONE -> 0.0f
    Severity.LOW -> 2.0f
    Severity.MEDIUM -> 4.0f
    Severity.HIGH -> 6.0f
}

fun Severity.label(): String = when (this) {
    Severity.NONE -> "HEALTHY"
    Severity.LOW -> "LOW"
    Severity.MEDIUM -> "MEDIUM"
    Severity.HIGH -> "HIGH"
}
