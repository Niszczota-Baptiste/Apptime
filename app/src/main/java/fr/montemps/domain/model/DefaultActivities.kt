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
        TrackedActivity(WORK, "Travail", 0xFF1E88E5.toInt(), "💼", 0, false),
        TrackedActivity(DRIVING, "Conduite", 0xFF6D4C41.toInt(), "🚗", 1, false),
        TrackedActivity(BREAK, "Pause", 0xFFFFB300.toInt(), "☕", 2, false),
        TrackedActivity(MEAL, "Repas", 0xFFE53935.toInt(), "🍽️", 3, false),
        TrackedActivity(KOREAN, "Coréen", 0xFF8E24AA.toInt(), "한", 4, false),
        TrackedActivity(SPORT, "Sport", 0xFF43A047.toInt(), "🏃", 5, false),
        TrackedActivity(WRITING, "Écriture", 0xFF00897B.toInt(), "✍️", 6, false),
        TrackedActivity(MUSIC, "Musique", 0xFFD81B60.toInt(), "🎵", 7, false),
        TrackedActivity(FREE_TIME, "Temps libre", 0xFF7CB342.toInt(), "🌿", 8, false),
        TrackedActivity(OTHER, "Autre", 0xFF757575.toInt(), "•", 9, false),
    )

    /** Favorites par défaut pour les actions rapides de la notification. */
    val defaultFavorites = listOf(BREAK, WORK)

    /** Palette proposée lors de la création ou modification d'une activité. */
    val palette: List<Int> = listOf(
        0xFF1E88E5, 0xFF3949AB, 0xFF8E24AA, 0xFFD81B60, 0xFFE53935, 0xFFF4511E,
        0xFFFFB300, 0xFF7CB342, 0xFF43A047, 0xFF00897B, 0xFF00ACC1, 0xFF6D4C41, 0xFF757575,
    ).map { it.toInt() }

    val iconChoices: List<String> = listOf(
        "💼", "🚗", "☕", "🍽️", "한", "🏃", "✍️", "🎵", "🌿", "•", "📚", "🧘", "🛒", "🏠",
        "💻", "📞", "🎮", "😴", "🚶", "🧹", "🎨", "🎸", "🚌", "❤️",
    )
}
