package fr.montemps.surfaces

import android.content.Context
import android.os.Bundle
import android.appwidget.AppWidgetManager
import android.view.View
import android.widget.RemoteViews
import fr.montemps.R
import fr.montemps.domain.model.CommandSource
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.report.Fmt
import fr.montemps.notification.TrackingIntents
import java.time.Duration

/** Taille réelle d'un widget (dp), fournie par le lanceur ; 0 si inconnue. */
data class WidgetSize(val widthDp: Int, val heightDp: Int) {
    /** Ligne « Depuis… · aujourd'hui… » : à partir de 110 dp de haut. */
    val showsInfo: Boolean get() = heightDp == 0 || heightDp >= 110

    /** Rangée des favorites : à partir de 170 dp de haut. */
    val showsFavorites: Boolean get() = heightDp == 0 || heightDp >= 170

    companion object {
        val Unknown = WidgetSize(0, 0)

        /** En portrait, le lanceur affiche le widget avec la largeur minimale et la hauteur maximale. */
        fun of(options: Bundle?): WidgetSize = if (options == null) Unknown else WidgetSize(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT),
        )
    }
}

/**
 * Rendu des widgets en RemoteViews classiques (pas de bibliothèque supplémentaire).
 *
 * - Le chronomètre est un `Chronometer` natif animé par le lanceur : aucune mise à jour périodique.
 *   Sa base est exprimée en temps monotone (`elapsedRealtime`) : elle est recalculée après un
 *   redémarrage ou un changement d'heure (voir `SystemEventReceiver`).
 * - Couleurs et fonds viennent de ressources (clair/sombre résolus par le lanceur) ; seule la
 *   pastille prend la couleur de l'activité.
 * - Chaque bouton envoie une PendingIntent explicite et immuable vers le récepteur non exporté.
 */
object WidgetRenderer {
    private const val ROW_HEIGHT_DP = 50
    private const val HEADER_DP = 52

    fun tracking(context: Context, state: SurfaceState, size: WidgetSize, elapsedRealtimeNow: Long): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_tracking)
        views.setOnClickPendingIntent(android.R.id.background, TrackingIntents.openApp(context))
        val active = state.active
        if (active != null) {
            val name = state.activeActivity?.name ?: active.activityId
            val elapsedMs = Duration.between(active.start, state.now).toMillis().coerceAtLeast(0)
            views.setViewVisibility(R.id.widget_dot, View.VISIBLE)
            views.setInt(R.id.widget_dot, "setColorFilter", state.activeActivity?.color ?: GREY)
            views.setViewVisibility(R.id.widget_title, View.VISIBLE)
            views.setTextViewText(R.id.widget_title, name)
            views.setViewVisibility(R.id.widget_idle_title, View.GONE)
            views.setViewVisibility(R.id.widget_chronometer, View.VISIBLE)
            views.setChronometer(R.id.widget_chronometer, elapsedRealtimeNow - elapsedMs, null, true)
            views.setViewVisibility(R.id.widget_idle_time, View.GONE)
            views.setViewVisibility(R.id.widget_stop, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.widget_stop, TrackingIntents.stop(context, active.id, CommandSource.Widget))
            views.setContentDescription(R.id.widget_stop, "Arrêter le suivi de $name")
            views.setTextViewText(R.id.widget_subtitle, "Depuis ${state.startLabel} · aujourd'hui")
            // Total du jour, animé lui aussi par le lanceur.
            views.setViewVisibility(R.id.widget_today, View.VISIBLE)
            views.setChronometer(R.id.widget_today, elapsedRealtimeNow - state.trackedToday.toMillis(), null, true)
            views.setViewVisibility(R.id.widget_info, if (size.showsInfo) View.VISIBLE else View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_dot, View.GONE)
            views.setViewVisibility(R.id.widget_title, View.GONE)
            views.setViewVisibility(R.id.widget_idle_title, View.VISIBLE)
            views.setViewVisibility(R.id.widget_chronometer, View.GONE)
            views.setViewVisibility(R.id.widget_idle_time, View.VISIBLE)
            views.setViewVisibility(R.id.widget_stop, View.GONE)
            views.setViewVisibility(R.id.widget_today, View.GONE)
            views.setTextViewText(R.id.widget_subtitle, "Aujourd'hui ${Fmt.short(state.trackedToday)}")
            val hasToday = !state.trackedToday.isZero
            views.setViewVisibility(R.id.widget_info, if (size.showsInfo && hasToday) View.VISIBLE else View.GONE)
        }
        views.removeAllViews(R.id.widget_favorites)
        val favorites = state.favorites.take(2)
        if (size.showsFavorites && favorites.isNotEmpty()) {
            favorites.forEach { views.addView(R.id.widget_favorites, button(context, it, isActive = false)) }
            views.setViewVisibility(R.id.widget_favorites, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_favorites, View.GONE)
        }
        return views
    }

    fun activities(context: Context, state: SurfaceState, size: WidgetSize): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_activities)
        views.setOnClickPendingIntent(android.R.id.background, TrackingIntents.openApp(context))
        val active = state.active
        if (active != null) {
            views.setViewVisibility(R.id.widget_stop, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.widget_stop, TrackingIntents.stop(context, active.id, CommandSource.Widget))
        } else {
            views.setViewVisibility(R.id.widget_stop, View.GONE)
        }
        val columns = if (size.widthDp in 1..219) 1 else 2
        val rows = if (size.heightDp > 0) ((size.heightDp - HEADER_DP) / ROW_HEIGHT_DP).coerceAtLeast(1) else Int.MAX_VALUE / 4
        val items = state.shortcuts.take((rows * columns).coerceAtMost(state.shortcuts.size))
        views.removeAllViews(R.id.widget_grid)
        items.chunked(columns).forEach { rowItems ->
            val row = RemoteViews(context.packageName, R.layout.widget_row)
            rowItems.forEach { row.addView(R.id.widget_row, button(context, it, isActive = it.id == active?.activityId)) }
            repeat(columns - rowItems.size) { row.addView(R.id.widget_row, RemoteViews(context.packageName, R.layout.widget_button_spacer)) }
            views.addView(R.id.widget_grid, row)
        }
        return views
    }

    /** Bouton « pastille + nom ». L'activité en cours est en négatif (fond encre), comme dans l'app. */
    private fun button(context: Context, activity: TrackedActivity, isActive: Boolean): RemoteViews {
        val layout = if (isActive) R.layout.widget_activity_button_active else R.layout.widget_activity_button
        return RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.widget_button_label, activity.name)
            setInt(R.id.widget_button_dot, "setColorFilter", activity.color)
            setOnClickPendingIntent(R.id.widget_button, TrackingIntents.switchTo(context, activity.id, CommandSource.Widget))
            setContentDescription(R.id.widget_button, if (isActive) "${activity.name}, en cours" else "Démarrer ${activity.name}")
        }
    }

    private const val GREY = 0xFF9A9AA0.toInt()
}
