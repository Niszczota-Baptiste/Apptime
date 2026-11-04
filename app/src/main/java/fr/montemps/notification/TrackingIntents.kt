package fr.montemps.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import fr.montemps.MainActivity
import fr.montemps.domain.model.CommandSource

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

    fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
