package com.laoda.proxy.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * LPX1 协议编解码, 与 docs/protocol.md、server/proxy_server.py 保持一致。
 * 帧: TYPE(1) + LEN(4, 大端) + PAYLOAD
 */
object ProxyProtocol {
    const val TYPE_CONNECT: Byte = 0x01
    const val TYPE_PING: Byte = 0x02
    const val TYPE_DATA: Byte = 0x03
    const val TYPE_CLOSE: Byte = 0x04

    fun handshake(out: DataOutputStream, token: String) {
        val t = token.toByteArray(Charsets.UTF_8)
        out.write("LPX1".toByteArray())
        out.writeByte(0x01)
        out.writeShort(t.size)
        out.write(t)
        out.flush()
    }

    fun sendFrame(out: DataOutputStream, type: Byte, payload: ByteArray = byteArrayOf()) {
        out.writeByte(type.toInt())
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    fun readFrame(input: DataInputStream): Pair<Byte, ByteArray> {
        val type = input.readByte()
        val len = input.readInt()
        require(len in 0..16 * 1024 * 1024) { "frame too large: $len" }
        val payload = ByteArray(len)
        if (len > 0) input.readFully(payload)
        return type to payload
    }

    fun connectPayload(host: String, port: Int): ByteArray {
        val h = host.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(1 + h.size + 2).order(ByteOrder.BIG_ENDIAN)
        buf.put(h.size.toByte())
        buf.put(h)
        buf.putShort(port.toShort())
        return buf.array()
    }

    /** 返回 RTT 毫秒, 失败返回 -1 */
    fun ping(input: DataInputStream, out: DataOutputStream): Long {
        val ts = System.currentTimeMillis()
        val payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putLong(ts).array()
        sendFrame(out, TYPE_PING, payload)
        val (type, _) = readFrame(input)
        check(type == TYPE_PING) { "unexpected ping reply: $type" }
        return System.currentTimeMillis() - ts
    }
}
