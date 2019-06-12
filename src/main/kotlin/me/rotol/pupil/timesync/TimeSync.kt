package me.rotol.pupil.timesync

import me.rotol.pupil.*
import org.zeromq.zyre.Zyre
import java.net.InetAddress
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

private const val PROTOCOL_VERSION = "v1"

class TimeSync(
    private val nodeName: String = InetAddress.getLocalHost().hostName,
    private var syncGroupPrefix: String = "default",
    existingNetwork: Zyre? = null,
    baseBias: Double = 1.0
) {
    companion object {
        @JvmStatic
        private val logger by LoggerDelegate()
    }

    private var currentOffset: Double = 0.0
    private var hasBeenMaster = 0.0
    private var hasBeenSynced = 0.0
    private val tieBreaker = Random.nextDouble()
    var baseBias = baseBias
        set(value) {
            field = value

            this.announceClockMasterInfo()
        }
    private val masterService = ClockSyncMaster(::getTime)
    private var followerService: ClockSyncFollower? = null
    private var discovery: Zyre? = null
    private var ourDiscovery: Boolean = existingNetwork != null
    private val leaderboard: PriorityQueue<ClockService> = PriorityQueue()
    val lockOffset = AtomicBoolean(false)

    private val rank
        get() = 4 * this.baseBias + 2 * this.hasBeenMaster + this.hasBeenSynced + this.tieBreaker

    private val syncGroup
        get() = "$syncGroupPrefix-time_sync-$PROTOCOL_VERSION"

    init {
        this.restartDiscovery(existingNetwork)
    }

    fun pollNetwork() {
        var shouldAnnounce = false

        this.discovery!!.recentEvents().forEach {
            if (it.type() == "SHOUT") {
                val multipart = try {
                    it.multipart().toList()
                } catch (e: Exception) {
                    logger.error("Error decoding multipart frame")
                    e.printStackTrace()
                    return@forEach
                }

                logger.info("Got shout $multipart")

                try {
                    this.updateLeaderboard(
                        it.peerUuid(),
                        it.peerName(),
                        multipart[0].toDouble(),
                        multipart[1].toInt()
                    )
                } catch (e: Exception) {
                    logger.error("Failed to process shout: $multipart")
                    e.printStackTrace()
                } finally {
                    this.evaluateLeaderboard()
                }
            } else if (it.type() == "JOIN" && it.group() == this.syncGroup) {
                shouldAnnounce = true
            } else if (it.type() == "LEAVE" && it.group() == this.syncGroup ||
                it.type() == "EXIT"
            ) {
                this.removeFromLeaderboard(it.peerUuid())
                this.evaluateLeaderboard()
            } else {
                /* Unhandled */
            }
        }

        if (this.hasBeenSynced == 0.0 && this.followerService != null && this.followerService!!.inSync) {
            this.hasBeenSynced = 1.0
            this.announceClockMasterInfo()
            this.evaluateLeaderboard()
        } else if (shouldAnnounce) {
            this.announceClockMasterInfo()
        } else {
            /* Nothing */
        }
    }

    fun changeSyncGroup(newGroupPrefix: String) {
        if (newGroupPrefix != this.syncGroupPrefix) {
            this.discovery!!.leave(this.syncGroup)
            this.leaderboard.clear()

            if (this.followerService != null) {
                this.followerService!!.terminate()
                this.followerService = null
            }

            this.syncGroupPrefix = newGroupPrefix
            this.discovery!!.join(this.syncGroup)
            this.announceClockMasterInfo()
        }
    }

    fun getTime(): Double {
        return System.currentTimeMillis() / 1000.0 + this.currentOffset
    }

    fun close() {
        this.discovery!!.leave(this.syncGroup)
        this.discovery!!.stop()
        this.discovery = null

        this.masterService.terminate()
//        this.masterService = null

        this.followerService?.terminate()
        this.followerService = null
    }

    private fun restartDiscovery(newNetwork: Zyre? = null) {
        if (this.discovery != null) {
            this.discovery!!.leave(this.syncGroup)

            if (this.ourDiscovery) {
                this.discovery!!.stop()
                this.discovery!!.close()
            }

            this.discovery = null

            this.leaderboard.clear()
        }

        this.ourDiscovery = newNetwork == null
        if (this.ourDiscovery) {
            this.discovery = Zyre(this.nodeName)
            this.discovery!!.join(this.syncGroup)
            this.discovery!!.start()
        } else {
            this.discovery = newNetwork
            this.discovery!!.join(this.syncGroup)
        }

        this.announceClockMasterInfo()
    }

    private fun announceClockMasterInfo() {
        this.discovery!!.shoutMultipart(
            this.syncGroup,
            "$rank",
            "${this.masterService.port}"
        )

        this.updateLeaderboard(
            this.discovery!!.uuid(), this.nodeName, this.rank, this.masterService.port
        )
    }

    private fun updateLeaderboard(uuid: String, name: String, rank: Double, port: Int) {
        val existing = this.leaderboard.firstOrNull { it.uuid == uuid }
        if (existing != null && (existing.rank != rank || existing.port != port)) {
            this.removeFromLeaderboard(existing.uuid)
        }

        val cs = ClockService(uuid, name, rank, port)
        this.leaderboard.add(cs)
        logger.debug("Added $cs to leaderboard")
    }

    private fun removeFromLeaderboard(uuid: String) {
        this.leaderboard.removeAll { it.uuid == uuid }
        logger.debug("Removed $uuid from leaderboard")
    }

    private fun evaluateLeaderboard() {
        if (this.leaderboard.isEmpty()) {
            logger.debug("The leaderboard is empty!")
            return
        }

        this.leaderboard.forEachIndexed { i, cs ->
            println("$i: rank=${cs.rank} ${cs.uuid}")
        }

        val currentLeader = this.leaderboard.first()
        if (this.discovery!!.uuid() != currentLeader.uuid) {
            val leaderHost = getIpv4(this.discovery!!.peerAddress(currentLeader.uuid))
            logger.debug("We aren't the leader, leaderHost=$leaderHost existingFollower=$followerService")

            if (this.followerService == null) {
                this.followerService = ClockSyncFollower(
                    leaderHost,
                    port = currentLeader.port,
                    interval = 10 * 1000,
                    timeFunction = ::getTime,
                    jumpFunction = ::jumpTime,
                    slewFunction = ::slewTime
                )
            } else {
                this.followerService!!.address = leaderHost
                this.followerService!!.port = currentLeader.port
            }
        } else {
            logger.debug("We are the leader")

            if (this.followerService != null) {
                this.followerService!!.terminate()
                this.followerService = null
            }

            if (this.hasBeenMaster == 0.0) {
                this.hasBeenMaster = 1.0
                logger.debug("First became clock master with rank $rank")
                this.announceClockMasterInfo()
            }
        }
    }

    private fun slewTime(offset: Double) {
        this.currentOffset -= offset
    }

    private fun jumpTime(offset: Double): Boolean {
        if (lockOffset.get()) {
            logger.error("Request to change time offset ignored as lockOffset is set!")
            return false
        }

        this.slewTime(offset)
        logger.info("The clock has been adjusted by ${offset}s (${currentOffset}s in total)")
        return true
    }
}
