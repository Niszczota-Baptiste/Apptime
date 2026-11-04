package fr.montemps.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.montemps.R
import fr.montemps.domain.model.CommandSource
import java.time.Instant

/** Contenu de la notification de suivi, calculé par [NotificationController]. */
data class TrackingNotificationContent(
    val sessionId: Long,
    val activityName: String,
    val activityColor: Int,
    val start: Instant,
    /** « 09:15 », « hier 23:10 »… */
    val startLabel: String,
    val favorites: List<FavoriteAction>,
    val hideNameOnLockScreen: Boolean,
    /** « Reprendre Travail » pendant une interruption. */
    val resume: FavoriteAction? = null,
    /** Demander l'affichage en « Live Update » (Android 16+ : puce dans la barre d'état). */
    val promoted: Boolean = false,
)

data class FavoriteAction(val activityId: String, val label: String)

/** Rappel « rien n'est suivi ». */
data class IdleContent(val sinceLabel: String, val actions: List<FavoriteAction>)

/** Résumé du soir. */
data class SummaryContent(val title: String, val text: String)

/** Rappel « session longue » (session peut-être oubliée). */
data class ReminderContent(
    val sessionId: Long,
    val activityName: String,
    val sinceLabel: String,
    val durationLabel: String,
    val hideNameOnLockScreen: Boolean,
)

/**
 * Construction et publication des notifications.
 *
 * Suivi en cours :
 * - chronomètre natif (`setUsesChronometer` + `setWhen(début)`) : le système fait avancer le
 *   temps lui-même, aucune republication périodique n'est nécessaire ;
 * - canal sans son ni vibration, `setOnlyAlertOnce` et `setSilent` : les mises à jour sont muettes ;
 * - `setOngoing(true)` : depuis Android 14 l'utilisateur peut tout de même la balayer (sauf écran
 *   verrouillé) ; on respecte ce choix (voir [NotificationController]).
 *
 * Rappel de session longue : canal séparé « Rappels », publié au plus une fois par session.
 */
class TrackingNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "tracking"
        const val REMINDER_CHANNEL_ID = "reminders"
        const val SUMMARY_CHANNEL_ID = "summaries"
        const val NOTIFICATION_ID = 1001
        const val REMINDER_ID = 1002
        const val IDLE_ID = 1003
        const val SUMMARY_ID = 1004
    }

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        system.createNotificationChannel(
            NotificationChannel(
                REMINDER_CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.reminder_channel_description)
                setShowBadge(false)
            },
        )
        system.createNotificationChannel(
            NotificationChannel(
                SUMMARY_CHANNEL_ID,
                context.getString(R.string.summary_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.summary_channel_description)
                setShowBadge(false)
            },
        )
    }

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Vrai si l'app peut effectivement afficher la notification de suivi (permission, app et canal autorisés). */
    fun canPost(): Boolean = canPostOn(CHANNEL_ID)

    private fun canPostOn(channelId: String): Boolean {
        if (!hasPermission() || !manager.areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(channelId)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun show(content: TrackingNotificationContent) = post(CHANNEL_ID, NOTIFICATION_ID) { build(content) }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    fun showReminder(content: ReminderContent) = post(REMINDER_CHANNEL_ID, REMINDER_ID) { buildReminder(content) }

    fun cancelReminder() = manager.cancel(REMINDER_ID)

    fun showIdle(content: IdleContent) = post(REMINDER_CHANNEL_ID, IDLE_ID) { buildIdle(content) }

    fun cancelIdle() = manager.cancel(IDLE_ID)

    fun showSummary(content: SummaryContent) = post(SUMMARY_CHANNEL_ID, SUMMARY_ID) { buildSummary(content) }

    /** Android 16+ : l'utilisateur autorise-t-il les « Live Updates » de l'app ? */
    fun canPostPromoted(): Boolean = runCatching { manager.canPostPromotedNotifications() }.getOrDefault(true)

    private inline fun post(channelId: String, id: Int, notification: () -> Notification) {
        if (!canPostOn(channelId)) return
        try {
            manager.notify(id, notification())
        } catch (_: SecurityException) {
            // Permission retirée entre la vérification et la publication : le suivi continue dans l'app.
        }
    }

    fun build(content: TrackingNotificationContent): Notification {
        val text = context.getString(R.string.notification_running_since, content.startLabel)
        val builder = baseBuilder(content)
            .setContentTitle(content.activityName)
            .setContentText(text)
            .setContentIntent(TrackingIntents.openApp(context))
            .setDeleteIntent(TrackingIntents.dismissed(context, content.sessionId))
            // Live Update (Android 16+) : notification promue, chronomètre visible dans la barre
            // d'état. Sans effet sur les versions antérieures ou si l'utilisateur l'a désactivé.
            .setRequestPromotedOngoing(content.promoted)

        content.resume?.let {
            builder.addAction(0, it.label, TrackingIntents.switchTo(context, it.activityId, CommandSource.Notification))
        }
        content.favorites.forEach { fav ->
            builder.addAction(0, fav.label, TrackingIntents.switchTo(context, fav.activityId, CommandSource.Notification))
        }
        builder.addAction(
            0,
            context.getString(R.string.action_stop_tracking),
            TrackingIntents.stop(context, content.sessionId, CommandSource.Notification),
        )

        if (content.hideNameOnLockScreen) {
            // Version publique affichée sur l'écran verrouillé : sans le nom de l'activité.
            val public = baseBuilder(content)
                .setContentTitle(context.getString(R.string.notification_public_title))
                .setContentText(text)
                .build()
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
        } else {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }
        return builder.build()
    }

    fun buildReminder(content: ReminderContent): Notification {
        val text = context.getString(R.string.reminder_text, content.sinceLabel, content.durationLabel)
        val builder = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle(context.getString(R.string.reminder_title, content.activityName))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setShowWhen(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setContentIntent(TrackingIntents.openApp(context))
            .addAction(0, context.getString(R.string.reminder_action_continue), TrackingIntents.acknowledge(context, content.sessionId))
            .addAction(0, context.getString(R.string.reminder_action_fix), TrackingIntents.openApp(context))
        if (content.hideNameOnLockScreen) {
            val public = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_tracking)
                .setContentTitle(context.getString(R.string.reminder_public_title))
                .build()
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
        } else {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }
        return builder.build()
    }

    fun buildIdle(content: IdleContent): Notification {
        val builder = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle(context.getString(R.string.idle_title, content.sinceLabel))
            .setContentText(context.getString(R.string.idle_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setShowWhen(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(TrackingIntents.openApp(context))
        content.actions.take(3).forEach {
            builder.addAction(0, it.label, TrackingIntents.switchTo(context, it.activityId, CommandSource.Notification))
        }
        return builder.build()
    }

    fun buildSummary(content: SummaryContent): Notification {
        val public = NotificationCompat.Builder(context, SUMMARY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle(context.getString(R.string.summary_public_title))
            .build()
        return NotificationCompat.Builder(context, SUMMARY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle(content.title)
            .setContentText(content.text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
            .setShowWhen(false)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(TrackingIntents.openApp(context, TrackingIntents.DESTINATION_REPORTS))
            .build()
    }

    private fun baseBuilder(content: TrackingNotificationContent) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setColor(content.activityColor)
            .setWhen(content.start.toEpochMilli())
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(false)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
}
