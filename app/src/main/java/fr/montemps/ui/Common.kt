package fr.montemps.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.montemps.AppContainer
import fr.montemps.MonTempsApp
import fr.montemps.domain.SessionError
import fr.montemps.domain.TransitionResult
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.report.Fmt
import fr.montemps.domain.report.PeriodReport
import fr.montemps.domain.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.ZoneId

/** Crée un ViewModel à partir du conteneur de l'application. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = (LocalContext.current.applicationContext as MonTempsApp).container
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

/**
 * Horloge d'*affichage* : émet l'heure courante à chaque seconde pleine tant que l'écran est
 * observé. Elle ne sert jamais à mesurer le temps suivi, uniquement à rafraîchir l'affichage.
 */
fun displayTicker(time: TimeSource, periodMs: Long = 1_000): Flow<Instant> = flow {
    while (true) {
        val now = time.now()
        emit(now)
        delay(periodMs - now.toEpochMilli() % periodMs)
    }
}

/** Couverture d'une période : le temps non suivi n'est jamais attribué à une activité. */
fun coverageText(report: PeriodReport): String = when {
    !report.hasStarted -> "Période à venir."
    report.isFullyTracked -> "Toute la période écoulée est suivie."
    else -> "Non suivi : ${Fmt.short(report.untracked)} sur ${Fmt.short(report.elapsed)} écoulées"
}

fun TransitionResult.Reason.message(): String = when (this) {
    TransitionResult.Reason.UNKNOWN_ACTIVITY -> "Cette activité n'existe plus."
    TransitionResult.Reason.ARCHIVED_ACTIVITY -> "Cette activité est archivée."
    TransitionResult.Reason.CLOCK_BEFORE_LAST_RECORD ->
        "L'heure du téléphone est antérieure à la dernière session enregistrée. Vérifiez l'heure système, puis réessayez."
    TransitionResult.Reason.INVALID_END_TIME -> "L'heure de fin doit être après le début et ne pas être dans le futur."
    TransitionResult.Reason.NOT_ACTIVE -> "Cette session n'est plus en cours."
    TransitionResult.Reason.START_TOO_EARLY -> "Impossible de démarrer avant la fin de la session précédente."
    TransitionResult.Reason.START_IN_FUTURE -> "L'heure de début ne peut pas être dans le futur."
}

fun SessionError.message(activities: Map<String, TrackedActivity>, zone: ZoneId): String = when (this) {
    SessionError.EndNotAfterStart -> "La fin doit être postérieure au début."
    SessionError.EndInFuture -> "Une session passée ne peut pas se terminer dans le futur."
    SessionError.StartInFuture -> "Le début ne peut pas être dans le futur."
    SessionError.UnknownActivity -> "Choisissez une activité valide."
    SessionError.NotFound -> "Cette session n'existe plus."
    SessionError.MissingEnd -> "Indiquez une heure de fin."
    SessionError.ActiveStartLocked ->
        "Le début d'une session en cours ne peut pas être modifié. Arrêtez le suivi, puis corrigez la session terminée."
    is SessionError.Overlap -> {
        val name = activities[other.activityId]?.name ?: other.activityId
        val date = Fmt.dayMedium(other.start.atZone(zone).toLocalDate())
        val end = other.end?.let { Fmt.time(it, zone) } ?: "en cours"
        "Chevauche la session « $name » ($date, ${Fmt.time(other.start, zone)} → $end)."
    }
}
