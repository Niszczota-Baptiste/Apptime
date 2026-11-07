package fr.montemps.surfaces

/** Ce que fait un appui sur la tuile du centre de contrôle. */
sealed interface TileAction {
    /** Arrêter la session affichée (et seulement elle). */
    data class Stop(val sessionId: Long) : TileAction

    /** Reprendre l'activité interrompue ou la dernière (avec sa sous-catégorie). */
    data class Resume(val activityId: String) : TileAction

    /** Rien à reprendre : ouvrir l'application pour choisir. */
    data object OpenApp : TileAction
}

/** Contenu de la tuile, calculé sans dépendance Android (testable). */
data class TileModel(
    val label: String,
    val subtitle: String,
    val active: Boolean,
    val description: String,
    val action: TileAction,
) {
    companion object {
        const val APP_NAME = "Mon Temps"

        fun from(state: SurfaceState): TileModel {
            val active = state.active
            val resume = state.resumeActivity
            if (active != null && resume != null) {
                // Pendant une interruption (Pause, Repas…), un appui reprend l'activité précédente.
                val name = state.activeLabel ?: active.activityId
                val target = state.resumeLabel ?: resume.name
                return TileModel(
                    label = name,
                    subtitle = "Reprendre $target",
                    active = true,
                    description = "$APP_NAME : $name en cours depuis ${state.startLabel}. Toucher pour reprendre $target.",
                    action = TileAction.Resume(resume.id),
                )
            }
            if (active != null) {
                val name = state.activeLabel ?: active.activityId
                return TileModel(
                    label = name,
                    subtitle = "Depuis ${state.startLabel}",
                    active = true,
                    description = "$APP_NAME : $name en cours depuis ${state.startLabel}. Toucher pour arrêter le suivi.",
                    action = TileAction.Stop(active.id),
                )
            }
            val last = state.lastActivity
            return if (last != null) {
                val target = state.lastLabel ?: last.name
                TileModel(
                    label = APP_NAME,
                    subtitle = "Reprendre $target",
                    active = false,
                    description = "$APP_NAME : aucun suivi. Toucher pour reprendre $target.",
                    action = TileAction.Resume(last.id),
                )
            } else {
                TileModel(
                    label = APP_NAME,
                    subtitle = "Aucun suivi",
                    active = false,
                    description = "$APP_NAME : aucun suivi. Toucher pour ouvrir l'application.",
                    action = TileAction.OpenApp,
                )
            }
        }
    }
}
