package fr.montemps.garmin

import fr.montemps.domain.model.SubActivity
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
            "switch" -> (map["id"] as? String)?.takeIf { it.isNotBlank() }?.let {
                WatchCommand.Switch(it, at, (map["sub"] as? String)?.takeIf { s -> s.isNotBlank() })
            }
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
            // Noms affichés avec la sous-catégorie : « Travail · Pro ».
            "a" to if (active != null && activity != null) {
                activityMap(activity) + mapOf(
                    "n" to (state.activeLabel ?: activity.name),
                    "s" to active.start.epochSecond.toInt(),
                    "i" to activity.interruption,
                    "sub" to active.subId,
                )
            } else {
                null
            },
            "td" to state.trackedToday.seconds.toInt(),
            // « Reprendre » : nom complet et sous-catégorie qui sera reprise (affichage immédiat hors ligne).
            "r" to state.resumeActivity?.let { activityMap(it) + mapOf("n" to (state.resumeLabel ?: it.name), "sub" to state.resumeSub?.id) },
            "l" to if (active == null) {
                state.lastActivity?.let { activityMap(it) + mapOf("n" to (state.lastLabel ?: it.name), "sub" to state.lastSub?.id) }
            } else {
                null
            },
            // Activités, et leurs sous-catégories (« subs ») pour le sous-menu de la montre.
            "acts" to state.shortcuts.map { a ->
                val subs = state.subsByActivity[a.id].orEmpty()
                if (subs.isEmpty()) activityMap(a) else activityMap(a) + mapOf("subs" to subs.map(::subMap))
            },
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
        append(state.active?.activityId).append('@').append(state.active?.start?.epochSecond).append('/').append(state.active?.subId).append('|')
        append(state.resumeLabel).append('|').append(state.lastLabel).append('|')
        state.shortcuts.forEach { append(it.id).append(':').append(it.name).append(':').append(rgb(it.color)).append(':').append(it.interruption).append(',') }
        state.subsByActivity.toSortedMap().forEach { (a, subs) -> append(a).append('=').append(subs.joinToString("+") { "${it.id}:${it.name}" }).append(';') }
        append('|').append(state.favorites.joinToString(",") { it.id })
    }

    private fun activityMap(a: TrackedActivity): Map<String, Any?> = mapOf("id" to a.id, "n" to a.name, "c" to rgb(a.color))

    private fun subMap(s: SubActivity): Map<String, Any> = mapOf("id" to s.id, "n" to s.name)

    /** Couleur Android (ARGB) → 0xRRGGBB, format des couleurs Connect IQ. */
    fun rgb(color: Int): Int = color and 0xFFFFFF

    /** Jour local du téléphone (AAAAMMJJ) : la montre masque le total du jour s'il ne correspond plus. */
    private fun dayKey(state: SurfaceState): Int {
        val d = state.now.atZone(state.zone).toLocalDate()
        return d.year * 10_000 + d.monthValue * 100 + d.dayOfMonth
    }
}
