package fr.montemps.domain.time

import java.time.Instant
import java.time.ZoneId

/**
 * Abstraction des horloges, injectée partout où l'heure est lue (testabilité).
 *
 * - [now] : instant calendaire (UTC), seule base de calcul des durées enregistrées.
 * - [zone] : fuseau courant, utilisé pour l'affichage et le découpage en jours/semaines.
 * - [elapsedRealtimeMs] / [bootCount] : horloge monotone (depuis le démarrage) et compteur de
 *   démarrages, utilisés seulement pour détecter un changement de l'heure système.
 */
interface TimeSource {
    fun now(): Instant
    fun zone(): ZoneId
    fun elapsedRealtimeMs(): Long
    fun bootCount(): Int?
}
