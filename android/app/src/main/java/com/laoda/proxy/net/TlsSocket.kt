package com.laoda.proxy.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * TLS 连接。生产必须校验服务端证书（系统 CA）。
 * 本地自签测试时, 调用方需自行做证书指纹钉扎, 不要直接关掉校验上生产。
 */
object TlsSocket {
    data class Conn(val socket: SSLSocket, val input: DataInputStream, val output: DataOutputStream)

    fun connect(host: String, port: Int, timeoutMs: Int = 5000): Conn {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val socket = factory.createSocket() as SSLSocket
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = 30000
        socket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
        socket.startHandshake()
        return Conn(socket, DataInputStream(socket.inputStream), DataOutputStream(socket.outputStream))
    }
}
