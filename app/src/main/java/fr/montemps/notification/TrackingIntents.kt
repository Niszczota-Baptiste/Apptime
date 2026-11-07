package fr.montemps.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import fr.montemps.MainActivity
import fr.montemps.domain.UndoToken
import fr.montemps.domain.model.CommandSource
import fr.montemps.domain.watch.WatchCommand
import java.time.Instant

/**
 * PendingIntent partagées par la notification, les widgets, la tuile et les rappels.
 *
 * Toutes sont explicites (vers [TrackingActionReceiver], non exporté, ou [MainActivity]) et
 * immuables. Une URI propre à chaque action/cible les distingue : mettre à jour l'une ne
 * remplace jamais les données d'une autre.
 */
object TrackingIntents {
    fun switchTo(context: Context, activityId: String, source: CommandSource): PendingIntent =
        broadcast(context, TrackingActionReceiver.ACTION_SWITCH, "switch", source.id, activityId) {
            putExtra(TrackingActionReceiver.EXTRA_ACTIVITY_ID, activityId)
            putExtra(TrackingActionReceiver.EXTRA_SOURCE, source.id)
        }

    /** « Reprendre » : l'activité interrompue (ou la dernière), avec sa sous-catégorie. */
    fun resume(context: Context, source: CommandSource): PendingIntent =
        broadcast(context, TrackingActionReceiver.ACTION_RESUME, "resume", source.id) {
            putExtra(TrackingActionReceiver.EXTRA_SOURCE, source.id)
        }

    /** Ouvre l'app sur le choix de sous-catégorie de [activityId] (widget « Activités »). */
    fun chooseSub(context: Context, activityId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(Uri.Builder().scheme("montemps").authority("choose").appendPath(activityId).build())
            .putExtra(MainActivity.EXTRA_CHOOSE_ACTIVITY, activityId)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Arrêt de la session [sessionId] uniquement : une action périmée n'arrête pas une session plus récente. */
    fun stop(context: Context, sessionId: Long, source: CommandSource): PendingIntent =
        broadcast(context, TrackingActionReceiver.ACTION_STOP, "stop", source.id) {
            putExtra(TrackingActionReceiver.EXTRA_SESSION_ID, sessionId)
            putExtra(TrackingActionReceiver.EXTRA_SOURCE, source.id)
        }

    fun dismissed(context: Context, sessionId: Long): PendingIntent =
        broadcast(context, TrackingActionReceiver.ACTION_DISMISSED, "dismissed") {
            putExtra(TrackingActionReceiver.EXTRA_SESSION_ID, sessionId)
        }

    fun refresh(context: Context): PendingIntent =
        broadcast(context, TrackingActionReceiver.ACTION_REFRESH, "refresh") { }

    fun reminder(context: Context, sessionId: Long): PendingIntent =
        broadcast(context, TrackingActionReceiver.ACTION_REMINDER, "reminder") {
            putExtra(TrackingActionReceiver.EXTRA_SESSION_ID, sessionId)
        }

    fun acknowledge(context: Context, sessionId: Long): PendingIntent =
        broadcast(context, TrackingActionReceiver.ACTION_ACKNOWLEDGE, "acknowledge") {
            putExtra(TrackingActionReceiver.EXTRA_SESSION_ID, sessionId)
        }

    fun idleReminder(context: Context): PendingIntent =
        broadcast(context, TrackingActionReceiver.ACTION_IDLE, "idle") { }

    fun eveningSummary(context: Context): PendingIntent =
        broadcast(context, TrackingActionReceiver.ACTION_SUMMARY, "summary") { }

    fun autoBackup(context: Context): PendingIntent =
        broadcast(context, TrackingActionReceiver.ACTION_AUTO_BACKUP, "backup") { }

    /** Alarme du coucher (ou du réveil) pour l'échéance [at] : appliquée à cette heure-là. */
    fun bedtime(context: Context, at: Instant? = null): PendingIntent =
        broadcast(context, TrackingActionReceiver.ACTION_BEDTIME, "bedtime") {
            at?.let { putExtra(TrackingActionReceiver.EXTRA_AT, it.toEpochMilli()) }
        }

    fun wake(context: Context, at: Instant? = null): PendingIntent =
        broadcast(context, TrackingActionReceiver.ACTION_WAKE, "wake") {
            at?.let { putExtra(TrackingActionReceiver.EXTRA_AT, it.toEpochMilli()) }
        }

    /** « Annuler » d'une notification : rétablit l'état d'avant la transition et retire la notification. */
    fun undo(context: Context, token: UndoToken, notificationId: Int): PendingIntent =
        broadcast(context, TrackingActionReceiver.ACTION_UNDO, "undo", notificationId.toString()) {
            putExtra(TrackingActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            token.startedSessionId?.let { putExtra(TrackingActionReceiver.EXTRA_STARTED_ID, it) }
            token.endedSessionId?.let { putExtra(TrackingActionReceiver.EXTRA_SESSION_ID, it) }
            token.endedAt?.let { putExtra(TrackingActionReceiver.EXTRA_AT, it.toEpochMilli()) }
        }

    fun undoTokenOf(intent: Intent): UndoToken = UndoToken(
        startedSessionId = intent.getLongExtra(TrackingActionReceiver.EXTRA_STARTED_ID, -1L).takeIf { it > 0 },
        endedSessionId = intent.getLongExtra(TrackingActionReceiver.EXTRA_SESSION_ID, -1L).takeIf { it > 0 },
        endedAt = intent.getLongExtra(TrackingActionReceiver.EXTRA_AT, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let(Instant::ofEpochMilli),
    )

    /**
     * Commande de la montre, envoyée depuis notre propre processus (diffusion explicite vers le
     * récepteur non exporté) : même chemin que les actions de la notification.
     */
    fun watchCommand(context: Context, command: WatchCommand): Intent {
        val intent = Intent(context, TrackingActionReceiver::class.java).setAction(TrackingActionReceiver.ACTION_WATCH)
        val (name, at) = when (command) {
            WatchCommand.Status -> "status" to null
            WatchCommand.Expired -> "expired" to null
            is WatchCommand.Switch -> {
                intent.putExtra(TrackingActionReceiver.EXTRA_ACTIVITY_ID, command.activityId)
                command.subId?.let { intent.putExtra(TrackingActionReceiver.EXTRA_SUB_ID, it) }
                "switch" to command.at
            }
            is WatchCommand.Stop -> "stop" to command.at
            is WatchCommand.Resume -> "resume" to command.at
        }
        intent.putExtra(TrackingActionReceiver.EXTRA_WATCH_COMMAND, name)
        at?.let { intent.putExtra(TrackingActionReceiver.EXTRA_AT, it.toEpochMilli()) }
        return intent
    }

    /** Inverse de [watchCommand] ; null si l'intention est incomplète. */
    fun watchCommandOf(intent: Intent): WatchCommand? {
        val at = intent.getLongExtra(TrackingActionReceiver.EXTRA_AT, Long.MIN_VALUE)
            .takeIf { it != Long.MIN_VALUE }?.let(Instant::ofEpochMilli)
        return when (intent.getStringExtra(TrackingActionReceiver.EXTRA_WATCH_COMMAND)) {
            "status" -> WatchCommand.Status
            "expired" -> WatchCommand.Expired
            "switch" -> intent.getStringExtra(TrackingActionReceiver.EXTRA_ACTIVITY_ID)?.let {
                WatchCommand.Switch(it, at, intent.getStringExtra(TrackingActionReceiver.EXTRA_SUB_ID))
            }
            "stop" -> WatchCommand.Stop(at)
            "resume" -> WatchCommand.Resume(at)
            else -> null
        }
    }

    const val DESTINATION_REPORTS = "reports"

    /** Ouvre l'app, éventuellement sur un écran donné (ex. [DESTINATION_REPORTS]). */
    fun openApp(context: Context, destination: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (destination != null) intent.putExtra(MainActivity.EXTRA_DESTINATION, destination)
        val requestCode = if (destination == null) 0 else 1 + destination.hashCode().and(0xFFFF)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private inline fun broadcast(
        context: Context,
        action: String,
        vararg path: String,
        extras: Intent.() -> Unit,
    ): PendingIntent {
        val uri = Uri.Builder().scheme("montemps").authority("action").apply { path.forEach { appendPath(it) } }.build()
        val intent = Intent(context, TrackingActionReceiver::class.java).setAction(action).setData(uri).apply(extras)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
