package com.example.meshcore.fuzz

import com.example.meshcore.model.ProtocolConstants.MAX_LORA_PHY_MTU
import kotlin.random.Random

enum class MutationType {
    BIT_FLIP,
    BYTE_REPLACEMENT,
    INSERTION,
    DELETION,
    TRUNCATION,
    BOUNDARY_VALUE,
    LENGTH_MISMATCH
}

data class MutationResult(
    val iteration: Int,
    val seed: Long,
    val type: MutationType,
    val mutatedBytes: ByteArray,
    val description: String,
    val diffIndices: List<Int>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MutationResult

        if (iteration != other.iteration) return false
        if (seed != other.seed) return false
        if (type != other.type) return false
        if (!mutatedBytes.contentEquals(other.mutatedBytes)) return false
        if (description != other.description) return false

        return true
    }

    override fun hashCode(): Int {
        var result = iteration
        result = 31 * result + seed.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + mutatedBytes.contentHashCode()
        result = 31 * result + description.hashCode()
        return result
    }
}

object MutationEngine {

    val BOUNDARY_BYTES: ByteArray = byteArrayOf(
        0x00.toByte(),
        0xFF.toByte(),
        0x7F.toByte(),
        0x80.toByte(),
        0x01.toByte(),
        0xFE.toByte()
    )

    /**
     * Deterministically mutates a baseline byte array given a base seed and iteration count.
     */
    fun mutate(
        baseline: ByteArray,
        seed: Long,
        iteration: Int,
        allowedTypes: Set<MutationType> = MutationType.values().toSet(),
        maxLen: Int = MAX_LORA_PHY_MTU
    ): MutationResult {
        require(baseline.isNotEmpty()) { "Baseline bytes cannot be empty" }
        require(allowedTypes.isNotEmpty()) { "Must provide at least one allowed mutation type" }

        // Derive deterministic PRNG for this specific iteration
        val rng = Random(seed + iteration * 31L)
        val typeList = allowedTypes.toList().sortedBy { it.name }
        val selectedType = typeList[rng.nextInt(typeList.size)]

        val copy = baseline.copyOf()
        val diffs = mutableListOf<Int>()
        val description: String
        val resultBytes: ByteArray

        when (selectedType) {
            MutationType.BIT_FLIP -> {
                val byteIdx = rng.nextInt(copy.size)
                val bitIdx = rng.nextInt(8)
                val mask = 1 shl bitIdx
                copy[byteIdx] = (copy[byteIdx].toInt() xor mask).toByte()
                diffs.add(byteIdx)
                description = "Flipped bit $bitIdx at byte index $byteIdx"
                resultBytes = copy
            }

            MutationType.BYTE_REPLACEMENT -> {
                val byteIdx = rng.nextInt(copy.size)
                var newByte = rng.nextInt(256).toByte()
                if (newByte == copy[byteIdx]) {
                    newByte = (newByte.toInt() xor 0xFF).toByte()
                }
                copy[byteIdx] = newByte
                diffs.add(byteIdx)
                description = "Replaced byte at $byteIdx with 0x%02X".format(newByte)
                resultBytes = copy
            }

            MutationType.INSERTION -> {
                if (copy.size >= maxLen) {
                    // Fall back to byte replacement if at max capacity
                    val byteIdx = rng.nextInt(copy.size)
                    copy[byteIdx] = (copy[byteIdx].toInt() xor 0xAA).toByte()
                    diffs.add(byteIdx)
                    description = "Max length reached; inverted byte at $byteIdx"
                    resultBytes = copy
                } else {
                    val insertPos = rng.nextInt(copy.size + 1)
                    val count = minOf(rng.nextInt(1, 4), maxLen - copy.size)
                    val inserted = ByteArray(copy.size + count)
                    System.arraycopy(copy, 0, inserted, 0, insertPos)
                    for (i in 0 until count) {
                        inserted[insertPos + i] = rng.nextInt(256).toByte()
                        diffs.add(insertPos + i)
                    }
                    System.arraycopy(copy, insertPos, inserted, insertPos + count, copy.size - insertPos)
                    description = "Inserted $count random bytes at position $insertPos"
                    resultBytes = inserted
                }
            }

            MutationType.DELETION -> {
                if (copy.size <= 1) {
                    // Cannot delete below 1 byte
                    copy[0] = (copy[0].toInt() xor 0x01).toByte()
                    diffs.add(0)
                    description = "Minimum size reached; toggled bit at 0"
                    resultBytes = copy
                } else {
                    val deletePos = rng.nextInt(copy.size)
                    val count = minOf(rng.nextInt(1, 3), copy.size - deletePos)
                    val deleted = ByteArray(copy.size - count)
                    System.arraycopy(copy, 0, deleted, 0, deletePos)
                    System.arraycopy(copy, deletePos + count, deleted, deletePos, copy.size - (deletePos + count))
                    for (i in 0 until count) diffs.add(deletePos + i)
                    description = "Deleted $count bytes starting at $deletePos"
                    resultBytes = deleted
                }
            }

            MutationType.TRUNCATION -> {
                if (copy.size <= 2) {
                    copy[copy.lastIndex] = 0x00
                    diffs.add(copy.lastIndex)
                    description = "Zeroed last byte"
                    resultBytes = copy
                } else {
                    val newLen = rng.nextInt(1, copy.size)
                    val truncated = copy.copyOfRange(0, newLen)
                    for (i in newLen until copy.size) diffs.add(i)
                    description = "Truncated frame from ${copy.size} to $newLen bytes"
                    resultBytes = truncated
                }
            }

            MutationType.BOUNDARY_VALUE -> {
                val byteIdx = rng.nextInt(copy.size)
                val boundary = BOUNDARY_BYTES[rng.nextInt(BOUNDARY_BYTES.size)]
                copy[byteIdx] = boundary
                diffs.add(byteIdx)
                description = "Injected boundary value 0x%02X at index $byteIdx".format(boundary)
                resultBytes = copy
            }

            MutationType.LENGTH_MISMATCH -> {
                // If packet looks like MeshCore wire frame [header][opt transport][path_len]...
                // Corrupt the declared length field deliberately to test parser robustness
                val targetIdx = if (copy.size > 5) 5 else copy.lastIndex
                val fakeLen = rng.nextInt(128, 255).toByte()
                copy[targetIdx] = fakeLen
                diffs.add(targetIdx)
                description = "Injected deliberate length-field mismatch (0x%02X) at index $targetIdx".format(fakeLen)
                resultBytes = copy
            }
        }

        return MutationResult(
            iteration = iteration,
            seed = seed,
            type = selectedType,
            mutatedBytes = resultBytes,
            description = description,
            diffIndices = diffs
        )
    }
}
