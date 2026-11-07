package fr.montemps.surfaces

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import fr.montemps.MainActivity
import fr.montemps.MonTempsApp
import fr.montemps.R
import fr.montemps.domain.model.CommandSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tuile du centre de contrôle / des réglages rapides.
 * - Suivi en cours : affiche l'activité ; un appui l'arrête.
 * - Sinon : un appui reprend la dernière activité (ou ouvre l'app s'il n'y en a pas).
 * Le service n'est lié que par le système (permission BIND_QUICK_SETTINGS_TILE).
 */
class TrackingTileService : TileService() {
    private val container get() = (applicationContext as MonTempsApp).container

    override fun onStartListening() {
        super.onStartListening()
        container.appScope.launch {
            val model = TileModel.from(container.surfaces.load())
            withContext(Dispatchers.Main) { render(model) }
        }
    }

    override fun onClick() {
        super.onClick()
        container.appScope.launch {
            when (val action = TileModel.from(container.surfaces.load()).action) {
                is TileAction.Stop -> container.tracker.stop(CommandSource.Tile, action.sessionId)
                is TileAction.Resume -> container.tracker.resume(CommandSource.Tile)
                TileAction.OpenApp -> withContext(Dispatchers.Main) { openApp() }
            }
            val model = TileModel.from(container.surfaces.load())
            withContext(Dispatchers.Main) { render(model) }
        }
    }

    private fun render(model: TileModel) {
        val tile = qsTile ?: return
        tile.label = model.label
        tile.state = if (model.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.contentDescription = model.description
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_tracking)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = model.subtitle
        tile.updateTile()
    }

    // Avant Android 14, startActivityAndCollapse(Intent) est la seule variante disponible ;
    // la version PendingIntent est utilisée dès qu'elle existe.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
