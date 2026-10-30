package org.teslatempo.history

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.teslatempo.planner.*
import java.time.Instant
import java.util.UUID

const val HISTORY_LIMIT = 50
enum class PlanOrigin(val label: String) { DEMO("Démonstration fictive"), PERSONAL("Simulation personnelle") }

/** A snapshot, never an executable command. No account, vehicle ID or weekly calendar. */
data class SavedRequest(
    val start: String, val horizon: String, val departure: String,
    val batteryPercent: Double, val targetPercent: Double, val capacityKwh: Double,
    val efficiency: Double, val currentAmps: Int,
) {
    fun request() = ChargingRequest(Instant.parse(start), Instant.parse(horizon), Instant.parse(departure),
        batteryPercent, targetPercent, capacityKwh, efficiency, currentAmps, availability = emptyList())
}
data class SavedSlot(val start: String, val end: String, val gridKwh: Double, val price: Price) {
    fun slot(): ChargeSlot {
        val from = Instant.parse(start); val to = Instant.parse(end)
        require(from < to && gridKwh.isFinite() && gridKwh > 0)
        require(price.period in Period.entries && (price.color == null || price.color in TempoColor.entries))
        require(price.eurosPerKwh.isFinite() && price.eurosPerKwh >= 0 && price.hcReference.isFinite() && price.hcReference >= 0)
        require(price.tariffId.isNotBlank() && price.tariffId.length <= 200 && price.source.isNotBlank() && price.source.length <= 1000)
        return ChargeSlot(from, to, gridKwh, price)
    }
}
data class SavedEstimate(val slots: List<SavedSlot>, val missingGridKwh: Double, val estimatedSoc: Double) {
    fun estimate(): PlanEstimate {
        require(slots.size <= 200 && missingGridKwh.isFinite() && missingGridKwh >= 0 && estimatedSoc.isFinite() && estimatedSoc in 0.0..100.0)
        val parsed = slots.map { it.slot() }
        require(parsed.zipWithNext().all { (a, b) -> a.end <= b.start })
        return PlanEstimate(parsed, missingGridKwh, estimatedSoc)
    }
}
data class SavedPlan(
    val id: String, val calculatedAt: String, val origin: PlanOrigin, val status: PlanStatus,
    val request: SavedRequest, val optimized: SavedEstimate, val fallback: SavedEstimate,
) {
    fun validate(): SavedPlan {
        UUID.fromString(id); Instant.parse(calculatedAt)
        require(origin in PlanOrigin.entries)
        require(status == PlanStatus.DRAFT || status == PlanStatus.TARGET_REACHED)
        val r = request.request()
        listOf(optimized, fallback).forEach { saved ->
            val e = saved.estimate()
            require(e.slots.all {
                it.start >= r.start && it.end <= r.horizon &&
                    it.gridKwh <= java.time.Duration.between(it.start, it.end).toNanos() / 3_600_000_000_000.0 * r.gridKw + 1e-6
            })
            val expectedSoc = if (status == PlanStatus.TARGET_REACHED) r.batteryPercent else
                minOf(r.targetPercent, r.batteryPercent + e.gridKwh * r.efficiency / r.capacityKwh * 100)
            require(kotlin.math.abs(e.estimatedSoc - expectedSoc) < 1e-6)
            if (status == PlanStatus.TARGET_REACHED) require(r.neededGridKwh < 1e-7 && e.slots.isEmpty())
            require(kotlin.math.abs(e.gridKwh + e.missingGridKwh - r.neededGridKwh) < 1e-6)
        }
        return this
    }
    companion object {
        fun capture(request: ChargingRequest, result: PlanComparison, origin: PlanOrigin, at: Instant): SavedPlan {
            require(!result.executionConfirmed && request.measuredGridKw == null)
            fun snapshot(e: PlanEstimate) = SavedEstimate(e.slots.map {
                SavedSlot(it.start.toString(), it.end.toString(), it.gridKwh, it.price.copy())
            }, e.missingGridKwh, e.estimatedSoc)
            return SavedPlan(UUID.randomUUID().toString(), at.toString(), origin, result.status,
                SavedRequest(request.start.toString(), request.horizon.toString(), request.departure.toString(),
                    request.batteryPercent, request.targetPercent, request.capacityKwh, request.efficiency, request.currentAmps),
                snapshot(result.optimized), snapshot(result.fallback)).validate()
        }
    }
}

object HistoryCodec {
    private val gson = Gson()
    fun validate(entries: List<SavedPlan>): List<SavedPlan> {
        require(entries.size <= HISTORY_LIMIT && entries.map { it.id }.distinct().size == entries.size)
        entries.forEach { it.validate() }
        return entries
    }
    fun encode(entries: List<SavedPlan>): String {
        validate(entries)
        val raw = "{\"schema\":1,\"plans\":" + gson.toJson(entries) + "}"
        require(raw.length <= 2_000_000)
        return raw
    }
    fun decode(raw: String): List<SavedPlan> {
        require(raw.length <= 2_000_000)
        val root = JsonParser.parseString(raw).asJsonObject
        require(root.get("schema").asInt == 1)
        return validate(gson.fromJson(root.get("plans"), Array<SavedPlan>::class.java).toList())
    }
    fun append(entries: List<SavedPlan>, entry: SavedPlan): List<SavedPlan> {
        validate(entries); entry.validate()
        if (entries.any { it.id == entry.id }) return entries
        require(entries.size < HISTORY_LIMIT) { "Historique plein : supprimez une simulation avant d’en enregistrer une nouvelle." }
        return (entries + entry).sortedByDescending { Instant.parse(it.calculatedAt) }
    }
}
