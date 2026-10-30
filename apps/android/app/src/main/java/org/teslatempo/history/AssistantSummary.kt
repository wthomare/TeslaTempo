package org.teslatempo.history

import java.util.Locale

/** Explicit allowlist: no dates, IDs, calendar, tariff source text or connection settings. */
fun assistantSummary(plan: SavedPlan): String {
    plan.validate()
    fun number(value: Double) = String.format(Locale.FRANCE, "%.2f", value)
    fun estimate(label: String, saved: SavedEstimate): String {
        val e = saved.estimate()
        return "$label : ${number(e.gridKwh)} kWh au compteur, coût estimé ${number(e.costEuros)} €, " +
            "batterie finale estimée ${number(e.estimatedSoc)} %, énergie manquante ${number(e.missingGridKwh)} kWh."
    }
    val r = plan.request
    val unknown = (plan.optimized.slots + plan.fallback.slots).any { it.price.color == null }
    return """
        Explique en français cette simulation TeslaTempo, sans proposer ni exécuter de commande véhicule.
        Origine : ${plan.origin.label}. Archive de simulation, pas une recharge mesurée ni un programme appliqué.
        Hypothèses : batterie ${number(r.batteryPercent)} %, cible ${number(r.targetPercent)} %, capacité utile supposée ${number(r.capacityKwh)} kWh, courant ${r.currentAmps} A, rendement supposé ${number(r.efficiency * 100)} %.
        ${estimate("Plan économique", plan.optimized)}
        ${estimate("Charge de secours", plan.fallback)}
        Couleur Tempo inconnue dans au moins un créneau retenu : ${if (unknown) "oui" else "non"}.
        Les tarifs sont ceux du calcul archivé ; leur actualité n'est pas garantie. Horaires et disponibilités volontairement omis : ces agrégats ne permettent pas de vérifier la faisabilité temporelle ni de recalculer un plan.
        Compare les deux estimations, souligne les hypothèses et les éventuels objectifs incomplets. N'invente pas de données manquantes ou d'économies réalisées. Toute modification doit être recalculée dans TeslaTempo.
    """.trimIndent()
}
