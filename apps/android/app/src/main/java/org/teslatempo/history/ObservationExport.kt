package org.teslatempo.history

import org.teslatempo.network.ObservationHistory

/** Measured snapshots only: no inferred sessions, no addition of cumulative counters. */
object ObservationExport {
    fun csv(history: ObservationHistory): String {
        require(history.samples.size <= 300)
        val states = setOf("Charging", "Starting", "Stopped", "Complete", "Disconnected", "NoPower")
        fun number(value: Double?, max: Double): String {
            require(value == null || (value.isFinite() && value in 0.0..max))
            return value?.toString() ?: ""
        }
        return buildString {
            append("# TeslaTempo: snapshots_only; cumulative counters must not be summed; missing values are empty; costs unknown\r\n")
            append("sampled_at_utc,state,battery_percent,session_counter_kwh,current_amps,cost_euros,simulated\r\n")
            history.samples.sortedBy { it.at }.forEach {
                append(listOf(it.at.toString(), it.state.takeIf(states::contains) ?: "Unknown", number(it.battery, 100.0),
                    number(it.sessionCounter, 500.0), number(it.current, 1000.0), "", history.simulated.toString()).joinToString(","))
                append("\r\n")
            }
        }
    }
}

/** Coverage indicators, not session reconstruction or measured energy totals. */
data class ObservationCoverage(val count: Int, val largestGapSeconds: Long?, val counterDecreases: Int)
fun observationCoverage(history: ObservationHistory): ObservationCoverage {
    val samples = history.samples.sortedBy { it.at }
    val pairs = samples.zipWithNext()
    return ObservationCoverage(samples.size,
        pairs.maxOfOrNull { (a, b) -> java.time.Duration.between(a.at, b.at).seconds },
        pairs.count { (a, b) -> a.sessionCounter != null && b.sessionCounter != null && b.sessionCounter < a.sessionCounter })
}
