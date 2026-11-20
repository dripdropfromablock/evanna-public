package com.evanna.player.sources.smb

import com.evanna.player.network.CancelScope
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

internal class SmbSocketFactory(
    private val address: SmbAddress,
    private val cancel: CancelScope,
) : SocketFactory(), Closeable {
    private val socket = Socket(Proxy.NO_PROXY)
    private val used = AtomicBoolean()
    private val stop = cancel.watch { runCatching { socket.close() } }

    override fun createSocket(host: String, port: Int): Socket {
        cancel.checkActive()
        if (!host.equals(address.host, true) || port != address.port || !used.compareAndSet(false, true)) {
            throw IOException("SMB_ADDRESS_CHANGED")
        }
        socket.connect(InetSocketAddress(host, port), 8_000)
        cancel.checkActive()
        return socket
    }

    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = blocked()
    override fun createSocket(host: InetAddress, port: Int): Socket = blocked()
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = blocked()
    private fun blocked(): Nothing = throw IOException("SMB_ADDRESS_CHANGED")

    override fun close() {
        stop()
        runCatching { socket.close() }
    }
}
