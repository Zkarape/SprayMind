package com.spraymind

import android.graphics.Bitmap

// dHash (difference hash): encodes the horizontal brightness gradient of an image
// as a 256-bit fingerprint stored in 4 Longs.
//
// Algorithm:
//   1. Resize the image to (HASH_SIZE+1) × HASH_SIZE — the extra column enables
//      HASH_SIZE pair-comparisons per row without wrapping.
//   2. For each pixel in the HASH_SIZE × HASH_SIZE grid, convert both the pixel and
//      its right neighbor to grayscale using ITU-R BT.601 integer coefficients.
//   3. Set bit = 1 if left luminance > right luminance, 0 otherwise.
//   4. Pack the 256 bits into 4 Longs.
//
// Hamming distance between two hashes measures how many gradient directions changed.
// ≤ 10 bits different (≥ 96 % match) means the frame content is effectively identical —
// same framing, same lighting, no meaningful pest or plant change detected.
//
// Why dHash over pHash or SSIM?
//   - dHash: O(N²) pixel reads, zero FFT, zero floating-point — takes <0.5 ms on a 16×16 grid.
//   - pHash: FFT over a 32×32 matrix — ~10× more ops, meaningfully slower on a hot SoC.
//   - SSIM: full sliding-window luminance + contrast computation — 100×+ more ops.
//   dHash is the correct choice here because we're comparing consecutive frames from a fixed
//   camera position where only content change matters, not global structural similarity.
object FrameHasher {

    private const val HASH_SIZE = 16   // 16 × 16 = 256-bit hash; matches 4 × Long.SIZE_BITS

    // Returns a 256-bit dHash as LongArray(4).
    // The input bitmap is NOT recycled — the caller owns its lifecycle.
    fun dhash(bitmap: Bitmap): LongArray {
        // Bilinear=false: nearest-neighbour is faster and equally valid for gradient comparison
        // at this extreme downscale; we are only preserving relative brightness, not texture.
        val thumb = Bitmap.createScaledBitmap(bitmap, HASH_SIZE + 1, HASH_SIZE, false)
        val hash = LongArray(4)
        var bit = 0

        for (y in 0 until HASH_SIZE) {
            for (x in 0 until HASH_SIZE) {
                val left  = thumb.getPixel(x,     y)
                val right = thumb.getPixel(x + 1, y)

                // ITU-R BT.601 integer luminance (avoids floats):
                //   Y = (77R + 150G + 29B) / 256   → coefficients sum to 256 ≈ 1.0
                val lumL = (left  shr 16 and 0xFF) * 77 + (left  shr 8 and 0xFF) * 150 + (left  and 0xFF) * 29
                val lumR = (right shr 16 and 0xFF) * 77 + (right shr 8 and 0xFF) * 150 + (right and 0xFF) * 29

                if (lumL > lumR) hash[bit / 64] = hash[bit / 64] or (1L shl (bit % 64))
                bit++
            }
        }

        thumb.recycle()
        return hash
    }

    // Hamming distance: number of bit positions that differ (0 = identical, 256 = inverse).
    fun hammingDistance(a: LongArray, b: LongArray): Int =
        (0 until 4).sumOf { java.lang.Long.bitCount(a[it] xor b[it]) }

    // Returns true when frames are similar enough to skip inference.
    // Threshold of 10/256 bits ≈ 96 % structural match; calibrated for:
    //   - Tolerance to minor leaf sway, lighting flicker, sensor noise
    //   - Sensitivity to a new pest colony, changed leaf colour, or camera movement
    fun isSimilar(a: LongArray, b: LongArray, threshold: Int = 10): Boolean =
        hammingDistance(a, b) <= threshold
}
