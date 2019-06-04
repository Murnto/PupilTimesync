package me.rotol.pupil.timesync

/**
 * Represents a remote clock service and is sortable by rank.
 */
data class ClockService(
    val uuid: String,
    val name: String,
    var rank: Double,
    val port: Int
) : Comparable<ClockService> {
    override fun compareTo(other: ClockService) =
        other.rank.compareTo(this.rank)
}