package com.edgeai.agent.services

import java.util.concurrent.atomic.AtomicLong

// Product: one typed IPC message. Subclasses only define type and payload
sealed class EdgeIpcFrame(val msgType: Short, val sequenceId: Long) {

    open val payload: ByteArray get() = EMPTY

    // Serializes to header (64 bytes) + payload; payload CRC32C is filled automatically
    fun toBytes(): ByteArray {
        val body = payload
        require(body.size <= EdgeIpcProtocol.MAX_PAYLOAD_SIZE) { "Payload exceeds daemon limit" }
        val header = EdgeIpcProtocol.createHeader(
            msgType = msgType,
            payloadLength = body.size,
            sequenceId = sequenceId,
            payloadCrc32 = EdgeIpcProtocol.computeCrc32c(body)
        )
        return header + body
    }

    class Ping(seq: Long) : EdgeIpcFrame(EdgeIpcProtocol.MSG_PING, seq)
    class Pong(seq: Long) : EdgeIpcFrame(EdgeIpcProtocol.MSG_PONG, seq)
    class Heartbeat(seq: Long) : EdgeIpcFrame(EdgeIpcProtocol.MSG_HEARTBEAT, seq)

    class JsonRpcRequest(seq: Long, val json: String) : EdgeIpcFrame(EdgeIpcProtocol.MSG_JSON_RPC_REQ, seq) {
        override val payload: ByteArray = json.toByteArray(Charsets.UTF_8)
    }

    class JsonRpcResponse(seq: Long, val json: String) : EdgeIpcFrame(EdgeIpcProtocol.MSG_JSON_RPC_RESP, seq) {
        override val payload: ByteArray = json.toByteArray(Charsets.UTF_8)
    }

    class Telemetry(seq: Long, val json: String) : EdgeIpcFrame(EdgeIpcProtocol.MSG_TELEMETRY, seq) {
        override val payload: ByteArray = json.toByteArray(Charsets.UTF_8)
    }

    class Error(seq: Long, val text: String) : EdgeIpcFrame(EdgeIpcProtocol.MSG_ERROR, seq) {
        override val payload: ByteArray = text.toByteArray(Charsets.UTF_8)
    }

    // Type not known to this client, kept as raw bytes
    class Unknown(type: Short, seq: Long, override val payload: ByteArray) : EdgeIpcFrame(type, seq)

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

// EdgeIpcFrameFactory: creates the right EdgeIpcFrame for a message type and decodes daemon replies
object EdgeIpcFrameFactory {

    // Monotonic per-process sequence counter
    private val sequence = AtomicLong(0)

    // Factory method: msgType -> concrete product
    fun create(
        msgType: Short,
        payload: ByteArray = ByteArray(0),
        sequenceId: Long = sequence.incrementAndGet()
    ): EdgeIpcFrame {
        fun text() = String(payload, Charsets.UTF_8)
        return when (msgType) {
            EdgeIpcProtocol.MSG_PING -> EdgeIpcFrame.Ping(sequenceId)
            EdgeIpcProtocol.MSG_PONG -> EdgeIpcFrame.Pong(sequenceId)
            EdgeIpcProtocol.MSG_HEARTBEAT -> EdgeIpcFrame.Heartbeat(sequenceId)
            EdgeIpcProtocol.MSG_JSON_RPC_REQ -> EdgeIpcFrame.JsonRpcRequest(sequenceId, text())
            EdgeIpcProtocol.MSG_JSON_RPC_RESP -> EdgeIpcFrame.JsonRpcResponse(sequenceId, text())
            EdgeIpcProtocol.MSG_TELEMETRY -> EdgeIpcFrame.Telemetry(sequenceId, text())
            EdgeIpcProtocol.MSG_ERROR -> EdgeIpcFrame.Error(sequenceId, text())
            else -> EdgeIpcFrame.Unknown(msgType, sequenceId, payload)
        }
    }

    fun ping() = create(EdgeIpcProtocol.MSG_PING)

    fun pong() = create(EdgeIpcProtocol.MSG_PONG)

    fun heartbeat() = create(EdgeIpcProtocol.MSG_HEARTBEAT)

    fun jsonRpcRequest(json: String) =
        create(EdgeIpcProtocol.MSG_JSON_RPC_REQ, json.toByteArray(Charsets.UTF_8))

    fun telemetry(json: String) =
        create(EdgeIpcProtocol.MSG_TELEMETRY, json.toByteArray(Charsets.UTF_8))

    // Reverse direction: validated header + payload bytes -> product
    fun decode(bytes: ByteArray): Result<EdgeIpcFrame> = runCatching {
        val header = EdgeIpcProtocol.parseHeader(bytes).getOrThrow()
        val end = EdgeIpcProtocol.HEADER_SIZE + header.payloadLength.toInt()
        require(bytes.size >= end) { "Truncated payload" }
        val payload = bytes.copyOfRange(EdgeIpcProtocol.HEADER_SIZE, end)
        require(EdgeIpcProtocol.computeCrc32c(payload) == header.payloadCrc32) { "Payload CRC mismatch" }
        create(header.msgType.toShort(), payload, header.sequenceId)
    }
}
