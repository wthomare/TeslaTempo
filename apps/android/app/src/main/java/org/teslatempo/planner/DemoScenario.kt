package org.teslatempo.planner

import java.time.LocalDate
import java.time.ZoneId

enum class DemoScenario(val label: String) { HOME("À domicile"), LEAVE("Absence à 8 h"), UNKNOWN("Couleur inconnue") }
data class Simulation(val request: ChargingRequest, val calendar: TempoCalendar, val tariffs: TariffVersion)
fun demoSimulation(day: LocalDate, soc: Double, capacity: Double, amps: Int, scenario: DemoScenario): Simulation {
    val zone = ZoneId.of("Europe/Paris")
    val start = day.atTime(22, 0).atZone(zone).toInstant()
    val departure = day.plusDays(1).atTime(8, 0).atZone(zone).toInstant()
    val horizon = day.plusDays(1).atTime(18, 0).atZone(zone).toInstant()
    val tariffs = TariffVersion("demo-v1", "Données fictives — non contractuelles", day, day.plusDays(2), mapOf(
        RateKey(TempoColor.BLUE, Period.HC) to 0.10, RateKey(TempoColor.BLUE, Period.HP) to 0.15,
        RateKey(TempoColor.WHITE, Period.HC) to 0.12, RateKey(TempoColor.WHITE, Period.HP) to 0.20,
        RateKey(TempoColor.RED, Period.HC) to 0.14, RateKey(TempoColor.RED, Period.HP) to 0.70,
    ))
    val colors = mutableMapOf(day to TempoColor.BLUE)
    if (scenario != DemoScenario.UNKNOWN) colors[day.plusDays(1)] = TempoColor.RED
    return Simulation(ChargingRequest(start, horizon, departure, soc, capacityKwh = capacity, currentAmps = amps,
        availability = listOf(Availability(start, if (scenario == DemoScenario.LEAVE) departure else horizon))),
        TempoCalendar(colors, listOf(tariffs)), tariffs)
}
