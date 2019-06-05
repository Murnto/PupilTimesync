package me.rotol.pupil.timesync

import me.rotol.pupil.LoggerDelegate
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.reflect.KFunction0
import kotlin.reflect.KFunction1

private val ms = 1 / 1000.0
private val us = ms * ms
private val tolerance = 0.1 * ms
private val maxSlew = 500 * us
private val minJump = 10 * ms
private val slewIterations = (minJump / maxSlew).toInt()
private val retryInterval = 1000L
private val slewInterval = 100L
private val SYNC_BUF = byteArrayOf(0x73, 0x79, 0x6E, 0x6C)

class ClockSyncFollower(
    var address: String,
    var port: Int,
    private val interval: Long,
    private val timeFunction: KFunction0<Double>,
    private val jumpFunction: KFunction1<Double, Boolean>,
    private val slewFunction: KFunction1<Double, Unit>
) {
    companion object {
        @JvmStatic
        private val logger by LoggerDelegate()
    }

    private val thread: Thread
    private var running = true
    private var syncJitter = 1000000.0
    private var offsetRemains = true
    var inSync = false
        private set

    init {
        this.thread = Thread(::run, "ClockSyncFollower")
        this.thread.isDaemon = true
        this.thread.start()
    }

    fun run() {
        logger.debug("Starting")

        while (this.running) {
            val result = getOffset()
            if (result != null) {
                val (offset, jitter) = result

                this.syncJitter = jitter
                if (abs(offset) > max(jitter, tolerance)) {
                    if (abs(offset) > minJump) {
                        if (this.jumpFunction(offset)) {
                            this.inSync = true
                            this.offsetRemains = false
                            logger.debug("Time adjusted by ${offset / ms}ms")
                        } else {
                            this.inSync = false
                            this.offsetRemains = true
                            Thread.sleep(retryInterval)
                            continue
                        }
                    } else {
                        for (x in 0 until slewIterations) {
                            val slewTime = max(-maxSlew, min(maxSlew, offset))
                            this.slewFunction(slewTime)
                            logger.debug("Time slewed by ${slewTime / ms}ms")

                            val slewedOffset = offset - slewTime
                            this.inSync = abs(slewedOffset) < us
                            this.offsetRemains = !this.inSync
                            if (!this.inSync) {
                                Thread.sleep(slewInterval)
                            }

                            break
                        }
                    }
                } else {
                    logger.debug("No clock adjustment")
                    this.inSync = true
                    this.offsetRemains = false
                }

                Thread.sleep(this.interval)
            } else {
                logger.debug("Failed to connect. Will retry")
                this.inSync = false
                Thread.sleep(retryInterval)
            }

            Thread.sleep(Random.nextLong(1000))
        }

        logger.debug("Stopped")
    }

    fun terminate() {
        logger.info("Terminating")

        this.running = false
        this.thread.join()
    }

    private fun getOffset(): Pair<Double, Double>? {
        try {
            val times: MutableList<Triple<Double, Double, Double>> = this.getHostOffsetTimes()

            val offsets = times
                .sortedBy { it.third - it.first }
                .take((times.size * 0.69).toInt())
                .map { it.first - (it.second + (it.third - it.first) / 2) }

            val avgOffset = offsets.average()
            val offsetJitter = offsets
                .map { abs(avgOffset - it) }
                .average()

            logger.info("Offset: ${avgOffset / ms} (${offsetJitter / ms})")

            return avgOffset to offsetJitter
        } catch (e: Exception) {
            logger.error("Failed to getOffset for $address:$port - ${e.message}")
            e.printStackTrace()
        }

        return null
    }

    private fun getHostOffsetTimes(): MutableList<Triple<Double, Double, Double>> {
        val readArray = ByteArray(8)
        val readBuffer = ByteBuffer.wrap(readArray)
            .order(ByteOrder.LITTLE_ENDIAN)
        val times: MutableList<Triple<Double, Double, Double>> = arrayListOf()

        Socket().use { s ->
            s.tcpNoDelay = true
            s.soTimeout = 1000
            s.connect(InetSocketAddress(InetAddress.getByName(this.address), this.port))
            val outputStream = s.getOutputStream()
            val inputStream = s.getInputStream()

            var count = 60
            while (count > 0) {
                count -= 1

                val t0 = this.timeFunction()
                outputStream.write(SYNC_BUF)
                outputStream.flush()

                inputStream.read(readArray)
                val t2 = this.timeFunction()

                readBuffer.rewind()
                val t1 = readBuffer.double

                times.add(Triple(t0, t1, t2))
            }
        }

        return times
    }
}
