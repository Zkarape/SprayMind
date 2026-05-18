package com.cropguard

import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.graphics.Bitmap

// Gemma 4 uses a variable-resolution vision system: the SigLIP encoder generates
// (Budget × 9) raw patches from the input image, then compresses them via 3×3 average
// pooling down to exactly Budget final tokens fed to the language decoder.
//
// Fewer tokens  → less GPU memory bandwidth, fewer attention ops → lower latency and battery draw.
// More tokens   → finer spatial detail preserved → better detection of small or subtle pests.
//
// ── Pixel math ────────────────────────────────────────────────────────────────────────────────
// SigLIP patch stride = 14 px.  Raw patches from a square image = (px / 14)².
// After 3×3 pool: final tokens = (px / 14)² / 9 → px = √(tokens × 9) × 14.
// targetPx is rounded to the nearest multiple of 14 so patches align exactly to the grid.
//
// ── Token budget → pixel size mapping ────────────────────────────────────────────────────────
//   70  tokens: √(  70 × 9) × 14 = √630  × 14 ≈ 25.1 × 14 = 350 px
//  140  tokens: √( 140 × 9) × 14 = √1260 × 14 ≈ 35.5 × 14 = 504 px  (36 × 14)
//  280  tokens: √( 280 × 9) × 14 = √2520 × 14 ≈ 50.2 × 14 = 700 px  (50 × 14)
//  560  tokens: √( 560 × 9) × 14 = √5040 × 14 ≈ 71.0 × 14 = 994 px  → capped at 896 (camera max)
// 1120  tokens: requires API-level token budget parameter in addition to pixel size;
//               the pixel dimension alone cannot exceed the SigLIP native tile of 896 px.
enum class ImageTokenBudget(
    val tokens: Int,
    val targetPx: Int   // image is resized to targetPx × targetPx before the encoder sees it
) {
    MINIMAL (  70, 350),   // emergency/critical thermal — fastest possible
    LOW     ( 140, 504),   // thermal throttling or battery < 15 %
    BALANCED( 280, 700),   // default for most field scans; ~40 % cheaper than FULL
    HIGH    ( 560, 896),   // nominal conditions; SigLIP native tile, maximum pixel quality
    FULL    (1120, 896);   // same pixel size as HIGH; full 1120-token budget needs LiteRT API param

    // ── Direct tensor utility ─────────────────────────────────────────────────────────────────
    //
    // Returns a direct ByteBuffer with normalized float pixels (R G B interleaved, [0.0 .. 1.0]).
    // Layout: targetPx × targetPx × 3 floats, native byte order.
    //
    // Use case: inject directly into LiteRT's low-level tensor API (e.g. future
    // Interpreter.run() or TensorBuffer path) without going through the high-level
    // Content.ImageFile / Content.Bitmap abstraction. SigLIP's internal preprocessing
    // then applies its own mean / std normalization on top of this [0, 1] input.
    //
    // The caller is responsible for recycling `bitmap` after this call if it is a temporary.
    fun prepareImageTensor(bitmap: Bitmap): ByteBuffer {
        val w = targetPx
        val h = targetPx

        // Only resize if the incoming bitmap doesn't already match our target dimensions.
        val source = if (bitmap.width == w && bitmap.height == h) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        }

        // Direct allocation bypasses the JVM heap — the GPU/DMA subsystem can read it
        // without an extra copy when passed to a native JNI buffer.
        val buffer = ByteBuffer.allocateDirect(w * h * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        if (source !== bitmap) source.recycle()

        for (px in pixels) {
            buffer.putFloat((px shr 16 and 0xFF) / 255.0f)   // R
            buffer.putFloat((px shr 8  and 0xFF) / 255.0f)   // G
            buffer.putFloat((px        and 0xFF) / 255.0f)   // B
        }

        buffer.rewind()  // caller reads from position 0
        return buffer
    }
}
