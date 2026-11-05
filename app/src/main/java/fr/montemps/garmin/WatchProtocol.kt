package fr.montemps.garmin

import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.watch.WatchCommand
import fr.montemps.surfaces.SurfaceState
import java.time.Duration
import java.time.Instant

/**
 * Messages échangés avec l'application Connect IQ « Mon Temps » (dossier garmin/ du dépôt).
 * Format décrit dans docs/GARMIN.md : dictionnaires Monkey C ⇄ Map Java, clés courtes (la liaison
 * Bluetooth est lente), heures en secondes depuis l'époque Unix, couleurs en 0xRRGGBB.
 *
 * Aucune dépendance Android : testable sur la JVM.
 */
object WatchProtocol {
    /** Identifiant de l'application Connect IQ (manifest.xml de garmin/). */
    const val APP_ID = "b01f1cbd-213b-4906-be10-3dd0691bb529"
    const val VERSION = 1

    /** En deçà, l'heure envoyée par la montre est considérée comme « maintenant » (horloges proches). */
    val NOW_TOLERANCE: Duration = Duration.ofSeconds(30)

    /** Au-delà, une commande restée dans la file hors ligne de la montre est ignorée. */
    val MAX_DELAY: Duration = Duration.ofDays(2)

    /** Message reçu de la montre → commande ; null si le message est illisible. */
    fun parse(message: Any?, now: Instant): WatchCommand? {
        val map = message as? Map<*, *> ?: return null
        val sentAt = (map["at"] as? Number)?.toLong()?.let(Instant::ofEpochSecond)
        val at = when {
            sentAt == null -> null
            sentAt.isAfter(now.minus(NOW_TOLERANCE)) -> null
            sentAt.isBefore(now.minus(MAX_DELAY)) -> return WatchCommand.Expired
            else -> sentAt
        }
        return when (map["c"]) {
            "status" -> WatchCommand.Status
            "switch" -> (map["id"] as? String)?.takeIf { it.isNotBlank() }?.let { WatchCommand.Switch(it, at) }
            "stop" -> WatchCommand.Stop(at)
            "resume" -> WatchCommand.Resume(at)
            else -> null
        }
    }

    /**
     * État envoyé à la montre. La montre calcule elle-même le chronomètre à partir du début de
     * session : aucun message n'est nécessaire chaque seconde.
     */
    fun encode(state: SurfaceState, message: String? = null): Map<String, Any?> {
        val active = state.active
        val activity = state.activeActivity
        return mapOf(
            "v" to VERSION,
            "t" to "state",
            "now" to state.now.epochSecond.toInt(),
            "d" to dayKey(state),
            "a" to if (active != null && activity != null) {
                activityMap(activity) + mapOf("s" to active.start.epochSecond.toInt(), "i" to activity.interruption)
            } else {
                null
            },
            "td" to state.trackedToday.seconds.toInt(),
            "r" to state.resumeActivity?.let(::activityMap),
            "l" to if (active == null) state.lastActivity?.let(::activityMap) else null,
            "acts" to state.shortcuts.map(::activityMap),
            "fav" to state.favorites.map { it.id },
            "msg" to message,
        )
    }

    /**
     * Partie stable de l'état (sans l'heure ni le total du jour, qui avancent seuls) : on n'envoie
     * un message que si elle change, ou si la montre le demande.
     */
    fun fingerprint(state: SurfaceState): String = buildString {
        append(dayKey(state)).append('|')
        append(state.active?.activityId).append('@').append(state.active?.start?.epochSecond).append('|')
        append(state.resumeActivity?.id).append('|').append(state.lastActivity?.id).append('|')
        state.shortcuts.forEach { append(it.id).append(':').append(it.name).append(':').append(rgb(it.color)).append(':').append(it.interruption).append(',') }
        append('|').append(state.favorites.joinToString(",") { it.id })
    }

    private fun activityMap(a: TrackedActivity): Map<String, Any> = mapOf("id" to a.id, "n" to a.name, "c" to rgb(a.color))

    /** Couleur Android (ARGB) → 0xRRGGBB, format des couleurs Connect IQ. */
    fun rgb(color: Int): Int = color and 0xFFFFFF

    /** Jour local du téléphone (AAAAMMJJ) : la montre masque le total du jour s'il ne correspond plus. */
    private fun dayKey(state: SurfaceState): Int {
        val d = state.now.atZone(state.zone).toLocalDate()
        return d.year * 10_000 + d.monthValue * 100 + d.dayOfMonth
    }
}
