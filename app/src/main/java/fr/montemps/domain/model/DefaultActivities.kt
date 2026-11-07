package fr.montemps.domain.model

/**
 * Activités créées lors de la première installation. Les identifiants sont stables et en
 * anglais (indépendants de la langue d'affichage) pour pouvoir être utilisés par de futures
 * applications compagnons (ex. l'app Coréen demandera « korean »).
 */
object DefaultActivities {
    const val WORK = "work"
    const val DRIVING = "driving"
    const val BREAK = "break"
    const val MEAL = "meal"
    const val KOREAN = "korean"
    const val SPORT = "sport"
    const val WRITING = "writing"
    const val MUSIC = "music"
    const val FREE_TIME = "free_time"
    const val OTHER = "other"
    /** Version 3 : démarré et arrêté automatiquement au coucher et au réveil (si activé). */
    const val SLEEP = "sleep"

    val all: List<TrackedActivity> = listOf(
        TrackedActivity(WORK, "Travail", 0xFF4A78E0.toInt(), null, 0, false),
        TrackedActivity(DRIVING, "Déplacement", 0xFF8D6E63.toInt(), null, 1, false),
        TrackedActivity(BREAK, "Pause", 0xFFE3A33B.toInt(), null, 2, false, interruption = true),
        TrackedActivity(MEAL, "Repas", 0xFFE07A5F.toInt(), null, 3, false, interruption = true),
        TrackedActivity(KOREAN, "Coréen", 0xFF8E7CC3.toInt(), null, 4, false),
        TrackedActivity(SPORT, "Sport", 0xFF3FA36B.toInt(), null, 5, false),
        TrackedActivity(WRITING, "Écriture", 0xFF2A9D8F.toInt(), null, 6, false),
        TrackedActivity(MUSIC, "Musique", 0xFFD1678E.toInt(), null, 7, false),
        TrackedActivity(FREE_TIME, "Temps libre", 0xFF8AB17D.toInt(), null, 8, false),
        TrackedActivity(OTHER, "Autre", 0xFF9A9AA0.toInt(), null, 9, false),
        TrackedActivity(SLEEP, "Sommeil", 0xFF607D8B.toInt(), null, 10, false),
    )

    /** Ancien nom de « Déplacement » (renommé par la migration s'il n'avait pas été modifié). */
    const val OLD_DRIVING_NAME = "Conduite"

    /**
     * Sous-catégories par défaut (version 3). Identifiants stables « activité_sous-catégorie ».
     * Ajoutées à la création de la base, par la migration 2 → 3 et à la restauration d'une
     * sauvegarde antérieure, seulement si l'activité correspondante existe.
     */
    val defaultSubs: List<SubActivity> = listOf(
        sub(WORK, "perso", "Perso", 0),
        sub(WORK, "pro", "Pro", 1),
        sub(BREAK, "youtube", "YouTube", 0),
        sub(BREAK, "game", "Jeu", 1),
        sub(BREAK, "other", "Autre", 2),
        sub(SPORT, "gym", "Muscu", 0),
        sub(SPORT, "football", "Foot", 1),
        sub(SPORT, "running", "Course", 2),
        sub(SPORT, "other", "Autre", 3),
        sub(WRITING, "write", "Écrire", 0),
        sub(WRITING, "read", "Lire", 1),
        sub(MUSIC, "composition", "Composition", 0),
        sub(MUSIC, "play", "Jouer", 1),
        sub(DRIVING, "car", "Voiture", 0),
        sub(DRIVING, "walk", "À pied", 1),
        sub(DRIVING, "transit", "Transports", 2),
        sub(DRIVING, "other", "Autre", 3),
    )

    private fun sub(activityId: String, key: String, name: String, order: Int) =
        SubActivity("${activityId}_$key", activityId, name, order)

    /** Activités d'interruption par défaut (pour les sauvegardes antérieures à cette notion). */
    val interruptionIds = setOf(BREAK, MEAL)

    /** Favorites par défaut pour les actions rapides de la notification. */
    val defaultFavorites = listOf(BREAK, WORK)

    /** Palette douce : lisible sur fond clair comme sombre, sans couleurs criardes. */
    val palette: List<Int> = listOf(
        0xFF4A78E0, 0xFF5C6BC0, 0xFF8E7CC3, 0xFFD1678E, 0xFFE07A5F, 0xFFE3A33B,
        0xFF8AB17D, 0xFF3FA36B, 0xFF2A9D8F, 0xFF4FA8C2, 0xFF8D6E63, 0xFF607D8B, 0xFF9A9AA0,
    ).map { it.toInt() }
}
