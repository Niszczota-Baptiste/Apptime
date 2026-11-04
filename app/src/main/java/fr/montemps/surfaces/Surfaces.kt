package fr.montemps.surfaces

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.service.quicksettings.TileService
import fr.montemps.MonTempsApp
import fr.montemps.domain.time.TimeSource
import fr.montemps.notification.AlarmScheduler
import fr.montemps.notification.TrackingIntents
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/**
 * Met à jour les surfaces hors de l'app (widgets, tuile) à partir de la base. Appelé via
 * `AppContainer.refreshOutputs` après chaque écriture, au démarrage, au redémarrage du téléphone,
 * lors d'un changement d'heure et juste après minuit (libellés « hier », total du jour).
 */
class SurfacesController(
    private val context: Context,
    private val loader: SurfaceStateLoader,
    private val alarms: AlarmScheduler,
    private val time: TimeSource,
) {
    private val mutex = Mutex()

    suspend fun refresh() = mutex.withLock {
        val state = loader.load()
        val manager = AppWidgetManager.getInstance(context)
        val trackingIds = ids(manager, TrackingWidgetProvider::class.java)
        val activitiesIds = ids(manager, ActivitiesWidgetProvider::class.java)
        val elapsed = time.elapsedRealtimeMs()
        trackingIds.forEach { id ->
            manager.updateAppWidget(id, WidgetRenderer.tracking(context, state, size(manager, id), elapsed))
        }
        activitiesIds.forEach { id ->
            manager.updateAppWidget(id, WidgetRenderer.activities(context, state, size(manager, id)))
        }
        // La tuile se met à jour quand le système la rend visible ; on le lui signale.
        runCatching { TileService.requestListeningState(context, ComponentName(context, TrackingTileService::class.java)) }

        val midnightRefresh = TrackingIntents.refresh(context)
        if (state.active != null || trackingIds.isNotEmpty() || activitiesIds.isNotEmpty()) {
            alarms.set(nextMidnight(state.now, state.zone).plusSeconds(30), midnightRefresh)
        } else {
            alarms.cancel(midnightRefresh)
        }
    }

    suspend fun load(): SurfaceState = loader.load()

    private fun ids(manager: AppWidgetManager, provider: Class<out AppWidgetProvider>): IntArray =
        runCatching { manager.getAppWidgetIds(ComponentName(context, provider)) }.getOrNull() ?: IntArray(0)

    private fun size(manager: AppWidgetManager, id: Int): WidgetSize =
        WidgetSize.of(runCatching { manager.getAppWidgetOptions(id) }.getOrNull())

    companion object {
        fun nextMidnight(now: Instant, zone: ZoneId): Instant =
            now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
    }
}

/** Base commune : toute mise à jour demandée par le système relit simplement la base. */
abstract class MonTempsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refresh(context)

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) =
        refresh(context)

    private fun refresh(context: Context) {
        val container = (context.applicationContext as MonTempsApp).container
        val pending: PendingResult? = goAsync()
        container.appScope.launch {
            try {
                container.surfaces.refresh()
            } finally {
                pending?.finish()
            }
        }
    }
}

/** Widget « Suivi en cours » : activité, chronomètre, Arrêter, favorites. */
class TrackingWidgetProvider : MonTempsWidgetProvider()

/** Widget « Activités » : toutes les activités, un appui pour changer. */
class ActivitiesWidgetProvider : MonTempsWidgetProvider()
