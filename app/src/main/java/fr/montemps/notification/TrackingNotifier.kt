package fr.montemps.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.montemps.MainActivity
import fr.montemps.R
import java.time.Instant

/** Contenu de la notification de suivi, calculé par [NotificationController]. */
data class TrackingNotificationContent(
    val sessionId: Long,
    val activityName: String,
    val activityIcon: String?,
    val activityColor: Int,
    val start: Instant,
    /** « 09:15 », « hier 23:10 »… */
    val startLabel: String,
    val favorites: List<FavoriteAction>,
    val hideNameOnLockScreen: Boolean,
)

data class FavoriteAction(val activityId: String, val label: String)

/**
 * Construction et publication de la notification de suivi.
 *
 * - Chronomètre natif (`setUsesChronometer` + `setWhen(début)`) : le système fait avancer le
 *   temps lui-même, aucune republication périodique n'est nécessaire.
 * - Canal sans son ni vibration, `setOnlyAlertOnce` et `setSilent` : les mises à jour sont muettes.
 * - `setOngoing(true)` : depuis Android 14 l'utilisateur peut tout de même la balayer (sauf écran
 *   verrouillé) ; on respecte ce choix (voir [NotificationController]).
 */
class TrackingNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "tracking"
        const val NOTIFICATION_ID = 1001
        private const val RC_OPEN = 0
        private const val RC_STOP = 10
        private const val RC_DISMISS = 11
        private const val RC_FAVORITE_BASE = 20
    }

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        val channel = NotificationChannel(
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
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Vrai si l'app peut effectivement afficher la notification (permission, app et canal autorisés). */
    fun canPost(): Boolean {
        if (!hasPermission() || !manager.areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun show(content: TrackingNotificationContent) {
        if (!canPost()) return
        try {
            manager.notify(NOTIFICATION_ID, build(content))
        } catch (_: SecurityException) {
            // Permission retirée entre la vérification et la publication : le suivi continue dans l'app.
        }
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    fun build(content: TrackingNotificationContent): Notification {
        val title = listOfNotNull(content.activityIcon, content.activityName).joinToString(" ")
        val text = context.getString(R.string.notification_running_since, content.startLabel)
        val builder = baseBuilder(content)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setDeleteIntent(actionIntent(NotificationActionReceiver.ACTION_DISMISSED, RC_DISMISS) {
                putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, content.sessionId)
            })

        content.favorites.forEachIndexed { index, fav ->
            builder.addAction(
                0,
                fav.label,
                actionIntent(NotificationActionReceiver.ACTION_SWITCH, RC_FAVORITE_BASE + index) {
                    putExtra(NotificationActionReceiver.EXTRA_ACTIVITY_ID, fav.activityId)
                },
            )
        }
        builder.addAction(
            0,
            context.getString(R.string.action_stop_tracking),
            actionIntent(NotificationActionReceiver.ACTION_STOP, RC_STOP) {
                putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, content.sessionId)
            },
        )

        if (content.hideNameOnLockScreen) {
            // Version publique affichée sur l'écran verrouillé : sans le nom de l'activité.
            val public = baseBuilder(content)
                .setContentTitle(context.getString(R.string.notification_public_title))
                .setContentText(context.getString(R.string.notification_running_since, content.startLabel))
                .build()
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
        } else {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }
        return builder.build()
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

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, RC_OPEN, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private inline fun actionIntent(action: String, requestCode: Int, extras: Intent.() -> Unit): PendingIntent {
        // Intent explicite vers un récepteur non exporté : aucune autre app ne peut déclencher ces actions.
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction(action).apply(extras)
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
