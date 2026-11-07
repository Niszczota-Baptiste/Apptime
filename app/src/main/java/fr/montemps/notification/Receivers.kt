package fr.montemps.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import fr.montemps.MonTempsApp
import fr.montemps.domain.model.CommandSource
import kotlinx.coroutines.launch

/**
 * Actions venant de la notification, des widgets, de la tuile, de la montre et des alarmes de
 * l'app. Non exporté : seules nos intentions explicites l'atteignent. Les actions répétées sont
 * idempotentes (passer à l'activité déjà active ou arrêter une session déjà arrêtée ne fait rien).
 */
class TrackingActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SWITCH = "fr.montemps.action.SWITCH"
        const val ACTION_STOP = "fr.montemps.action.STOP"
        const val ACTION_DISMISSED = "fr.montemps.action.DISMISSED"
        const val ACTION_REFRESH = "fr.montemps.action.REFRESH"
        const val ACTION_REMINDER = "fr.montemps.action.REMINDER"
        const val ACTION_ACKNOWLEDGE = "fr.montemps.action.ACKNOWLEDGE"
        const val ACTION_IDLE = "fr.montemps.action.IDLE"
        const val ACTION_SUMMARY = "fr.montemps.action.SUMMARY"
        const val ACTION_AUTO_BACKUP = "fr.montemps.action.AUTO_BACKUP"
        const val ACTION_WATCH = "fr.montemps.action.WATCH"
        const val ACTION_RESUME = "fr.montemps.action.RESUME"
        const val ACTION_BEDTIME = "fr.montemps.action.BEDTIME"
        const val ACTION_WAKE = "fr.montemps.action.WAKE"
        const val ACTION_UNDO = "fr.montemps.action.UNDO"
        const val EXTRA_STARTED_ID = "started_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_ACTIVITY_ID = "activity_id"
        const val EXTRA_SUB_ID = "sub_id"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_WATCH_COMMAND = "watch_command"
        const val EXTRA_AT = "at"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as MonTempsApp).container
        val pending: PendingResult? = goAsync()
        container.appScope.launch {
            try {
                val source = CommandSource.ofSurface(intent.getStringExtra(EXTRA_SOURCE))
                val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L).takeIf { it > 0 }
                when (intent.action) {
                    ACTION_SWITCH -> intent.getStringExtra(EXTRA_ACTIVITY_ID)?.let { container.tracker.switchTo(it, source) }
                    ACTION_STOP -> container.tracker.stop(source, sessionId)
                    ACTION_RESUME -> container.tracker.resume(source)
                    ACTION_BEDTIME, ACTION_WAKE -> {
                        val at = intent.getLongExtra(EXTRA_AT, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let(java.time.Instant::ofEpochMilli)
                        if (at != null) {
                            if (intent.action == ACTION_BEDTIME) container.sleep.fireBedtime(at) else container.sleep.fireWake(at)
                        }
                    }
                    ACTION_UNDO -> {
                        container.tracker.undo(TrackingIntents.undoTokenOf(intent))
                        container.notifier.cancelSleep(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
                    }
                    ACTION_DISMISSED -> sessionId?.let { container.notifications.onUserDismissed(it) }
                    ACTION_REMINDER -> sessionId?.let { container.reminders.fire(it) }
                    ACTION_ACKNOWLEDGE -> sessionId?.let { container.reminders.acknowledge(it) }
                    ACTION_IDLE -> container.idle.fire()
                    ACTION_SUMMARY -> container.summary.fire()
                    // La sauvegarde due est faite par refreshOutputs() ci-dessous.
                    ACTION_REFRESH, ACTION_AUTO_BACKUP -> Unit
                    // Commande de la montre ; la réponse (état + éventuel refus) part avec refreshOutputs().
                    ACTION_WATCH -> TrackingIntents.watchCommandOf(intent)?.let { command ->
                        container.watch.reply(container.watchCommands.handle(command))
                    }
                    else -> return@launch
                }
                // Toujours réaligner notification, widgets, tuile, montre et alarmes sur la base.
                if (intent.action != ACTION_DISMISSED) container.refreshOutputs()
                // Garder le processus en vie jusqu'à la fin de l'envoi vers la montre.
                container.watch.awaitSync()
            } finally {
                pending?.finish()
            }
        }
    }
}

/**
 * Événements système (diffusions protégées, émises uniquement par Android) : redémarrage, mise à
 * jour de l'app, changement d'heure ou de fuseau. Tout est recalculé depuis la base (notification,
 * widgets, tuile, alarmes — celles-ci sont effacées par un redémarrage) ; aucune donnée n'est modifiée.
 */
class SystemEventReceiver : BroadcastReceiver() {
    private val handled = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIMEZONE_CHANGED,
        Intent.ACTION_TIME_CHANGED,
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in handled) return
        val container = (context.applicationContext as MonTempsApp).container
        val pending: PendingResult? = goAsync()
        container.appScope.launch {
            try {
                container.refreshOutputs()
            } finally {
                pending?.finish()
            }
        }
    }
}
