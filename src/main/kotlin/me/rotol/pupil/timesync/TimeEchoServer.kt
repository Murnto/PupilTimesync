package me.rotol.pupil.timesync

import me.rotol.pupil.LoggerDelegate
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.*
import kotlin.math.log


private typealias Handler = (selector: Selector, key: SelectionKey) -> Unit

class TimeEchoServer(
    private val timeFunction: () -> Double
) {
    companion object {
        @JvmStatic
        private val logger by LoggerDelegate()
    }

    private lateinit var server: ServerSocketChannel
    private lateinit var selector: Selector
    private lateinit var thread: Thread
    private val readBuffer = ByteBuffer.allocate(4)
    private val timeBuffer = ByteBuffer.allocate(8)
        .order(ByteOrder.LITTLE_ENDIAN)
    var port: Int = -1

    fun start() {
        this.server = ServerSocketChannel.open()
        this.server.setOption(StandardSocketOptions.SO_REUSEADDR, true)
        this.server.bind(InetSocketAddress(0))
        this.server.configureBlocking(false)

        this.port = (this.server.localAddress as InetSocketAddress).port
        logger.info("Set port to $port")

        this.selector = Selector.open()

        this.server.register(this.selector, SelectionKey.OP_ACCEPT, ::handleClientAccept)

        this.thread = Thread(::run, "TimeEchoServer")
        this.thread.isDaemon = true
        this.thread.start()
    }

    private fun handleClientAccept(sel: Selector, key: SelectionKey) {
        if (key.isAcceptable) {
            val client = (key.channel() as ServerSocketChannel).accept()
            client.configureBlocking(false)

            client.register(sel, SelectionKey.OP_READ, ::handleClient)
        }
    }

    private fun handleClient(sel: Selector, key: SelectionKey) {
        if (!key.isValid || !key.isReadable) {
            logger.warn("Cancelled at 1")
            key.cancel()
            return
        }

        val client = key.channel() as SocketChannel

        readBuffer.rewind()
        val amount = client.read(readBuffer)

        if (amount == -1) {
            key.cancel()
            logger.debug("Connection to ${client.remoteAddress} was closed.")
            return
        }

        timeBuffer.putDouble(0, this.timeFunction())
        timeBuffer.rewind()
        client.write(timeBuffer)
    }

    @Suppress("UNCHECKED_CAST")
    fun run() {
        logger.info("Starting server thread on port ${this.port}")

        while (selector.select() > 0) {
            val keys = selector.selectedKeys().iterator()
            while (keys.hasNext()) {
                val key = keys.next()
                try {
                    (key.attachment() as Handler)(selector, key)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                } finally {
                    keys.remove()
                }
            }
        }

        logger.info("Stopped server thread")
    }

    fun stop() {
        this.server.close()
        this.selector.close()
        this.thread.join()
    }
}
