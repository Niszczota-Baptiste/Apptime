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

    val all: List<TrackedActivity> = listOf(
        TrackedActivity(WORK, "Travail", 0xFF4A78E0.toInt(), "💼", 0, false),
        TrackedActivity(DRIVING, "Conduite", 0xFF8D6E63.toInt(), "🚗", 1, false),
        TrackedActivity(BREAK, "Pause", 0xFFE3A33B.toInt(), "☕", 2, false),
        TrackedActivity(MEAL, "Repas", 0xFFE07A5F.toInt(), "🍽️", 3, false),
        TrackedActivity(KOREAN, "Coréen", 0xFF8E7CC3.toInt(), "한", 4, false),
        TrackedActivity(SPORT, "Sport", 0xFF3FA36B.toInt(), "🏃", 5, false),
        TrackedActivity(WRITING, "Écriture", 0xFF2A9D8F.toInt(), "✍️", 6, false),
        TrackedActivity(MUSIC, "Musique", 0xFFD1678E.toInt(), "🎵", 7, false),
        TrackedActivity(FREE_TIME, "Temps libre", 0xFF8AB17D.toInt(), "🌿", 8, false),
        TrackedActivity(OTHER, "Autre", 0xFF9A9AA0.toInt(), "•", 9, false),
    )

    /** Favorites par défaut pour les actions rapides de la notification. */
    val defaultFavorites = listOf(BREAK, WORK)

    /** Palette proposée lors de la création ou modification d'une activité. */
    /** Palette douce : lisible sur fond clair comme sombre, sans couleurs criardes. */
    val palette: List<Int> = listOf(
        0xFF4A78E0, 0xFF5C6BC0, 0xFF8E7CC3, 0xFFD1678E, 0xFFE07A5F, 0xFFE3A33B,
        0xFF8AB17D, 0xFF3FA36B, 0xFF2A9D8F, 0xFF4FA8C2, 0xFF8D6E63, 0xFF607D8B, 0xFF9A9AA0,
    ).map { it.toInt() }

    val iconChoices: List<String> = listOf(
        "💼", "🚗", "☕", "🍽️", "한", "🏃", "✍️", "🎵", "🌿", "•", "📚", "🧘", "🛒", "🏠",
        "💻", "📞", "🎮", "😴", "🚶", "🧹", "🎨", "🎸", "🚌", "❤️",
    )
}
