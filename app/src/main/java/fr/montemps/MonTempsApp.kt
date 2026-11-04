package fr.montemps

import android.app.Application
import kotlinx.coroutines.launch

class MonTempsApp : Application() {
    /** Remplaçable uniquement par les tests (horloge factice, base en mémoire). */
    lateinit var container: AppContainer
        @androidx.annotation.VisibleForTesting set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.ensureChannel()
        // Processus recréé (après destruction par Android, mise à jour…) : la session active est
        // relue depuis la base et la notification resynchronisée. Aucune donnée n'est modifiée.
        container.appScope.launch { container.notifications.sync() }
    }
}
