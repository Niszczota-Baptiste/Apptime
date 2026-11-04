package fr.montemps.surfaces

import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import fr.montemps.R

/**
 * Demandes d'ajout à l'écran d'accueil (widget) et au centre de contrôle (tuile), proposées par
 * Android quand le lanceur / l'interface système le permettent. Sinon, un message explique la
 * manipulation manuelle.
 */
object HomeScreenRequests {
    const val MANUAL_WIDGET = "Ajout direct non proposé par l'écran d'accueil : appui long sur l'écran d'accueil → Widgets → Mon Temps."
    const val MANUAL_TILE = "Ouvrez le centre de contrôle, touchez Modifier, puis ajoutez la tuile Mon Temps."

    /** @return false si le lanceur ne propose pas l'ajout direct. */
    fun pinWidget(context: Context, provider: Class<out AppWidgetProvider>): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false
        return manager.requestPinAppWidget(ComponentName(context, provider), null, null)
    }

    fun addTile(context: Context, onResult: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onResult(MANUAL_TILE)
            return
        }
        val statusBar = context.getSystemService(StatusBarManager::class.java)
        if (statusBar == null) {
            onResult(MANUAL_TILE)
            return
        }
        statusBar.requestAddTileService(
            ComponentName(context, TrackingTileService::class.java),
            context.getString(R.string.tile_label),
            Icon.createWithResource(context, R.drawable.ic_stat_tracking),
            context.mainExecutor,
        ) { result ->
            onResult(
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tuile ajoutée au centre de contrôle."
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "La tuile est déjà dans le centre de contrôle."
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Tuile non ajoutée."
                    else -> MANUAL_TILE
                },
            )
        }
    }
}
