package com.example.meshcore.util

import java.util.Locale

object HexUtil {

    /**
     * Parses a hex string into a ByteArray.
     * Accepts strings with or without spaces, newlines, or 0x prefixes.
     * Throws IllegalArgumentException on invalid hexadecimal characters or odd length.
     */
    fun parseHex(hex: String): ByteArray {
        val clean = hex.replace("0x", "", ignoreCase = true)
            .replace(Regex("[^0-9A-Fa-f]"), "")
        if (clean.isEmpty()) return ByteArray(0)
        require(clean.length % 2 == 0) { "Hex string has odd length: ${clean.length} digits" }

        val result = ByteArray(clean.length / 2)
        for (i in clean.indices step 2) {
            val byteStr = clean.substring(i, i + 2)
            result[i / 2] = byteStr.toInt(16).toByte()
        }
        return result
    }

    /**
     * Converts a ByteArray to an uppercase spaced hex string.
     */
    fun toHexString(bytes: ByteArray, separator: String = " "): String {
        return bytes.joinToString(separator) { String.format(Locale.US, "%02X", it) }
    }

    /**
     * Converts a ByteArray to a compact hex string with no spaces.
     */
    fun toCompactHex(bytes: ByteArray): String {
        return toHexString(bytes, separator = "")
    }

    data class DiffItem(
        val index: Int,
        val originalByte: Byte?,
        val mutatedByte: Byte?
    )

    /**
     * Highlights differences between original and mutated byte arrays.
     */
    fun computeDiff(original: ByteArray, mutated: ByteArray): List<DiffItem> {
        val maxLen = maxOf(original.size, mutated.size)
        val diffs = mutableListOf<DiffItem>()
        for (i in 0 until maxLen) {
            val orig = original.getOrNull(i)
            val mut = mutated.getOrNull(i)
            if (orig != mut) {
                diffs.add(DiffItem(i, orig, mut))
            }
        }
        return diffs
    }
}
