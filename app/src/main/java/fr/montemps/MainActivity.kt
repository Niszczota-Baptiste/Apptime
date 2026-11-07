package fr.montemps

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import fr.montemps.ui.MonTempsNavHost
import fr.montemps.ui.theme.MonTempsTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        /** Écran à ouvrir (ex. « reports » depuis le résumé du soir). */
        const val EXTRA_DESTINATION = "destination"

        /** Activité dont proposer les sous-catégories (widget « Activités »). */
        const val EXTRA_CHOOSE_ACTIVITY = "choose_activity"
    }

    private val destination = mutableStateOf<String?>(null)
    private val chooseActivity = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) read(intent)
        setContent {
            MonTempsTheme {
                MonTempsNavHost(
                    destination = destination.value,
                    onDestinationHandled = { destination.value = null },
                    chooseActivity = chooseActivity.value,
                    onChooseHandled = { chooseActivity.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent) {
        destination.value = intent.getStringExtra(EXTRA_DESTINATION)
        chooseActivity.value = intent.getStringExtra(EXTRA_CHOOSE_ACTIVITY)
    }

    override fun onResume() {
        super.onResume()
        // L'utilisateur a l'app sous les yeux : le rappel « rien n'est suivi » repart de maintenant.
        val container = (application as MonTempsApp).container
        container.appScope.launch { container.idle.onAppOpened() }
    }
}
