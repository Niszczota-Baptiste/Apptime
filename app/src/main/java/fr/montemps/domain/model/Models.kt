package fr.montemps.domain.model

import java.time.Duration
import java.time.Instant

/**
 * Activité suivie (Travail, Pause…).
 *
 * [id] est un identifiant stable (ex. « work », « korean » ou un UUID pour les activités créées
 * par l'utilisateur). Il ne change jamais : les sessions, les sauvegardes et les futures
 * applications compagnons y font référence. Le [name] est purement affichable : renommer une
 * activité renomme donc aussi son historique (voir docs/ARCHITECTURE.md).
 */
data class TrackedActivity(
    val id: String,
    val name: String,
    /** Couleur ARGB (0xFFRRGGBB). */
    val color: Int,
    /** Icône sous forme de court texte/émoji, facultative. */
    val icon: String?,
    val sortOrder: Int,
    val archived: Boolean,
)

/**
 * Session de suivi : intervalle semi-ouvert [start, end).
 *
 * - [start] et [end] sont des instants UTC (non ambigus).
 * - [end] == null signifie que la session est en cours.
 * - [zoneId] est le fuseau du téléphone au démarrage (informatif ; les bilans utilisent le fuseau
 *   courant de l'appareil).
 * - [startElapsedRealtimeMs]/[startBootCount] : horloge monotone au démarrage, utilisée uniquement
 *   pour détecter une modification de l'heure système pendant un même démarrage du téléphone.
 */
data class Session(
    val id: Long = 0,
    val activityId: String,
    val start: Instant,
    val end: Instant?,
    val zoneId: String,
    val note: String? = null,
    val startElapsedRealtimeMs: Long? = null,
    val startBootCount: Int? = null,
    val source: String = CommandSource.App.id,
) {
    val isActive: Boolean get() = end == null

    /** Durée de la session ; pour une session active, durée écoulée jusqu'à [now]. */
    fun duration(now: Instant): Duration {
        val effectiveEnd = end ?: now
        return if (effectiveEnd.isAfter(start)) Duration.between(start, effectiveEnd) else Duration.ZERO
    }
}

/** Origine d'une commande de suivi (enregistrée sur la session, utile au diagnostic). */
sealed class CommandSource(val id: String) {
    data object App : CommandSource("app")
    data object Notification : CommandSource("notification")
    data object Manual : CommandSource("manual")
    data object Restore : CommandSource("restore")
    /** Future application compagnon identifiée par son nom de paquet. */
    data class External(val packageName: String) : CommandSource("external:$packageName")
}
