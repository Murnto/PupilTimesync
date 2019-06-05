package me.rotol.pupil.timesync

import me.rotol.pupil.LoggerDelegate

class ClockSyncMaster(
    private val timeFunction: () -> Double
) {
    companion object {
        @JvmStatic
        private val logger by LoggerDelegate()
    }

    private val timeEchoServer: TimeEchoServer

    init {
        logger.info("Starting")
        this.timeEchoServer = TimeEchoServer(this.timeFunction)
        this.timeEchoServer.start()
    }

    fun terminate() {
        logger.info("Terminating")

        this.timeEchoServer.stop()
    }

    val port get() = this.timeEchoServer.port
}
