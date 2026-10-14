package air.stemsync.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** theta is HOST minus GUEST. hostNow = guestNow + theta. All clocks are monotonic ns. */
class ClockEstimator {
    data class Sample(val offsetNs: Double, val rttNs: Long, val receivedNs: Long)
    private val samples = ArrayDeque<Sample>()
    var offsetNs = 0.0; private set
    var lastSampleNs: Long? = null; private set
    val count get() = samples.size
    val ready get() = count >= 5
    val rttMs get() = samples.map { it.rttNs / 1e6 }.average().takeIf { it.isFinite() }
    val jitterMs get() = rttMs?.let { mean -> sqrt(samples.sumOf { (it.rttNs / 1e6 - mean).pow(2) } / samples.size) }
    fun add(t1: Long, t2: Long, t3: Long, t4: Long, playing: Boolean = false): Boolean {
        if (listOf(t1,t2,t3,t4).any { it < 0 } || t4 < t1 || t3 < t2) return false
        val elapsed = t4 - t1
        val processing = t3 - t2
        if (elapsed > 5_000_000_000L || processing > elapsed) return false
        val rtt = elapsed - processing
        val theta = ((t2.toDouble() - t1) + (t3.toDouble() - t4)) / 2
        samples.addLast(Sample(theta, rtt, t4))
        while (samples.size > 24) samples.removeFirst()
        // Median of the five lowest RTT samples limits queueing outliers.
        val best = samples.sortedBy { it.rttNs }.take(5).map { it.offsetNs }.sorted()
        val estimate = best[best.size / 2]
        offsetNs = if (lastSampleNs == null || !playing) estimate else offsetNs + (estimate - offsetNs).coerceIn(-250_000.0, 250_000.0)
        lastSampleNs = t4
        return true
    }
    fun hostTime(localNs: Long) = localNs + offsetNs.toLong()
    fun fresh(nowNs: Long) = ready && lastSampleNs?.let { nowNs - it in 0..10_000_000_000L } == true
    fun clear() { samples.clear(); offsetNs = 0.0; lastSampleNs = null }
}

@Serializable
data class Timeline(
    val sessionId: String, val trackId: String, val playbackRevision: Long,
    val playing: Boolean, val basePositionMs: Long, val effectiveHostTimeNs: Long,
    val durationMs: Long, val playbackRate: Double = 1.0
) {
    fun valid() = sessionId.isNotBlank() && trackId.isNotBlank() && playbackRevision >= 0 &&
        durationMs in 1..3_600_000 && basePositionMs in 0..durationMs && effectiveHostTimeNs >= 0 && playbackRate == 1.0
    fun positionAt(hostNs: Long): Long = (basePositionMs + if (playing) {
        ((hostNs - effectiveHostTimeNs).coerceAtLeast(0) / 1e6 * playbackRate).toLong()
    } else 0).coerceIn(0, durationMs)
}

/** Keep the prior timeline active until a future revision takes effect. */
class TimelineStore {
    var current: Timeline? = null; private set
    var pending: Timeline? = null; private set
    val latest get() = pending ?: current
    fun accept(next: Timeline): Boolean {
        if (!next.valid()) return false
        val old = latest
        if (old != null && (next.sessionId != old.sessionId || next.playbackRevision <= old.playbackRevision)) return false
        pending = next
        return true
    }
    fun at(hostNs: Long): Timeline? {
        pending?.let { if (hostNs >= it.effectiveHostTimeNs) { current = it; pending = null } }
        return current
    }
    fun clear() { current = null; pending = null }
}

class DriftController {
    data class Correction(val speed: Float = 1f, val seek: Boolean = false)
    private var persistent = 0
    private var priorSign = 0
    fun update(errorMs: Long): Correction {
        val sign = errorMs.compareTo(0)
        if (abs(errorMs) < 25) { reset(); return Correction() }
        persistent = if (sign == priorSign) persistent + 1 else 1
        priorSign = sign
        if (persistent < 3) return Correction()
        if (abs(errorMs) > 250) { reset(); return Correction(seek = true) }
        return Correction(if (errorMs > 0) .995f else 1.005f)
    }
    fun reset() { persistent = 0; priorSign = 0 }
}
