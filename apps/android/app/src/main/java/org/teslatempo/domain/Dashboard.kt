package org.teslatempo.domain

import java.time.Instant

data class Vehicle(val id: String, val name: String, val state: String)
data class ChargeState(val battery: Int?, val limit: Int?, val current: Int?, val state: String?)
data class Dashboard(
    val simulated: Boolean,
    val vehicles: List<Vehicle>,
    val selected: Vehicle?,
    val charge: ChargeState?,
    // Time received on the phone, not the time measured by Tesla.
    val receivedAt: Instant = Instant.now(),
)

interface VehicleGateway {
    fun load(selectedId: String? = null): Dashboard
}

class DemoGateway : VehicleGateway {
    override fun load(selectedId: String?): Dashboard {
        val car = Vehicle("demo", "Model Y — démonstration", "online")
        return Dashboard(true, listOf(car), car, ChargeState(55, 80, 10, "Stopped"))
    }
}
