package fr.montemps

import android.app.Application
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MonTempsApp : Application() {
    /** Remplaçable uniquement par les tests (horloge factice, base en mémoire). */
    lateinit var container: AppContainer
        @androidx.annotation.VisibleForTesting set

    /** Réalignement initial (notification, widgets, tuile, alarmes) ; les tests peuvent l'attendre. */
    lateinit var startup: Job
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.ensureChannels()
        // Processus recréé (après destruction par Android, mise à jour…) : la session active est
        // relue depuis la base ; notification, widgets et tuile sont réalignés. Rien n'est modifié.
        startup = container.appScope.launch { container.refreshOutputs() }
    }
}
