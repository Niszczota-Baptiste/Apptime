package fr.montemps.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import fr.montemps.MonTempsApp
import fr.montemps.domain.model.CommandSource
import kotlinx.coroutines.launch

/**
 * Actions de la notification (non exporté : seules nos PendingIntent l'atteignent).
 * Les actions répétées sont idempotentes : passer à l'activité déjà active ou arrêter une
 * session déjà arrêtée ne fait rien.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SWITCH = "fr.montemps.action.SWITCH"
        const val ACTION_STOP = "fr.montemps.action.STOP"
        const val ACTION_DISMISSED = "fr.montemps.action.DISMISSED"
        const val EXTRA_ACTIVITY_ID = "activity_id"
        const val EXTRA_SESSION_ID = "session_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as MonTempsApp).container
        val pending: PendingResult? = goAsync()
        container.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_SWITCH -> intent.getStringExtra(EXTRA_ACTIVITY_ID)?.let {
                        container.tracker.switchTo(it, CommandSource.Notification)
                    }
                    ACTION_STOP -> {
                        val expected = intent.getLongExtra(EXTRA_SESSION_ID, -1L).takeIf { it > 0 }
                        container.tracker.stop(CommandSource.Notification, expected)
                    }
                    ACTION_DISMISSED -> {
                        val id = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                        if (id > 0) container.notifications.onUserDismissed(id)
                    }
                }
                if (intent.action != ACTION_DISMISSED) container.notifications.sync()
            } finally {
                pending?.finish()
            }
        }
    }
}

/**
 * Événements système (diffusions protégées, émises uniquement par Android) : redémarrage, mise à
 * jour de l'app, changement d'heure ou de fuseau. On se contente de resynchroniser la
 * notification à partir de la base ; aucune donnée n'est modifiée.
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
                container.notifications.sync()
            } finally {
                pending?.finish()
            }
        }
    }
}
