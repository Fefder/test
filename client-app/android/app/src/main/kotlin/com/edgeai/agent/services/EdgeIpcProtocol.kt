package com.edgeai.agent.services

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Parsed view of the 64-byte header (unsigned fields widened to signed types)
data class EdgeIpcHeader(
    val version: Int,
    val msgType: Int,
    val flags: Int,
    val payloadLength: Long,
    val sequenceId: Long,
    val timestampNs: Long,
    val headerCrc32: Int,
    val payloadCrc32: Int
)

// EdgeIpcProtocol: 64-byte wire framing codec compatible with ipc_protocol.h v1
object EdgeIpcProtocol {

    const val HEADER_SIZE = 64
    const val HEADER_CRC_OFFSET = 32
    const val MAGIC: Long = 0x45444745L // 'E', 'D', 'G', 'E' in Little-Endian
    const val VERSION: Short = 1
    const val MAX_PAYLOAD_SIZE = 16 * 1024 * 1024 // daemon limit (EdgeIpcHeaderCodec.MaxPayloadSize)
    const val MSG_PING: Short = 0x0001
    const val MSG_PONG: Short = 0x0002
    const val MSG_HEARTBEAT: Short = 0x0003
    const val MSG_JSON_RPC_REQ: Short = 0x0010
    const val MSG_JSON_RPC_RESP: Short = 0x0011
    const val MSG_TELEMETRY: Short = 0x0030
    const val MSG_ERROR: Short = 0x00FF

    // Castagnoli CRC32C polynomial (0x82F63B78 reflected)
    private val CRC32C_TABLE = IntArray(256) { i ->
        var crc = i
        for (j in 0 until 8) {
            crc = if ((crc and 1) != 0) {
                (crc ushr 1) xor 0x82F63B78.toInt()
            } else {
                crc ushr 1
            }
        }
        crc
    }

    // Computes CRC32C (Castagnoli) for given byte range
    fun computeCrc32c(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        if (length <= 0) return 0
        var crc = -1 // 0xFFFFFFFF
        val end = offset + length
        for (i in offset until end) {
            val byteVal = data[i].toInt() and 0xFF
            crc = (crc ushr 8) xor CRC32C_TABLE[(crc xor byteVal) and 0xFF]
        }
        return crc.inv()
    }

    // Creates 64-byte binary frame header
    fun createHeader(
        msgType: Short,
        payloadLength: Int = 0,
        sequenceId: Long = 1L,
        flags: Short = 0,
        payloadCrc32: Int = 0
    ): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(MAGIC.toInt())           // [0..3]: Magic 0x45444745
        buffer.putShort(VERSION)               // [4..5]: Version 1
        buffer.putShort(msgType)               // [6..7]: MessageType
        buffer.putShort(flags)                 // [8..9]: Flags
        buffer.putShort(0.toShort())           // [10..11]: ReservedPad (must be 0)
        buffer.putInt(payloadLength)           // [12..15]: PayloadLength
        buffer.putLong(sequenceId)             // [16..23]: SequenceId
        buffer.putLong(System.nanoTime())      // [24..31]: TimestampNs

        // Calculate CRC32C of bytes [0..31]
        val headerBytes = buffer.array()
        val headerCrc = computeCrc32c(headerBytes, 0, HEADER_CRC_OFFSET)

        buffer.putInt(headerCrc)               // [32..35]: HeaderCrc32
        buffer.putInt(payloadCrc32)            // [36..39]: PayloadCrc32
        // Bytes [40..63] are 0-padded reserved bytes

        return headerBytes
    }

    // Validates and parses a header, mirrors EdgeIpcHeaderCodec.TryParseHeader
    fun parseHeader(bytes: ByteArray): Result<EdgeIpcHeader> {
        if (bytes.size < HEADER_SIZE) {
            return Result.failure(IllegalArgumentException("Header too short: ${bytes.size}"))
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.getInt(0).toLong() and 0xFFFFFFFFL
        val version = buf.getShort(4).toInt() and 0xFFFF
        val payloadLength = buf.getInt(12).toLong() and 0xFFFFFFFFL
        val headerCrc = buf.getInt(HEADER_CRC_OFFSET)

        return when {
            magic != MAGIC -> Result.failure(IllegalArgumentException("Bad magic"))
            version != VERSION.toInt() -> Result.failure(IllegalArgumentException("Bad version $version"))
            payloadLength > MAX_PAYLOAD_SIZE -> Result.failure(IllegalArgumentException("Payload too large"))
            headerCrc != computeCrc32c(bytes, 0, HEADER_CRC_OFFSET) ->
                Result.failure(IllegalArgumentException("Header CRC mismatch"))
            else -> Result.success(
                EdgeIpcHeader(
                    version = version,
                    msgType = buf.getShort(6).toInt() and 0xFFFF,
                    flags = buf.getShort(8).toInt() and 0xFFFF,
                    payloadLength = payloadLength,
                    sequenceId = buf.getLong(16),
                    timestampNs = buf.getLong(24),
                    headerCrc32 = headerCrc,
                    payloadCrc32 = buf.getInt(36)
                )
            )
        }
    }
}
