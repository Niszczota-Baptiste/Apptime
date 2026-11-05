package fr.montemps.ui.workouts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.montemps.ui.components.PillButton
import fr.montemps.ui.theme.MonTempsTheme

/**
 * Explication de l'autorisation Health Connect, affichée par Android depuis l'écran des
 * autorisations (obligatoire pour pouvoir les demander). Texte seul : aucune donnée n'est lue ici.
 */
class HealthPermissionRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MonTempsTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Séances sportives", style = MaterialTheme.typography.headlineMedium)
                        RATIONALE.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        PillButton("Fermer", onClick = { finish() }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    companion object {
        val RATIONALE = listOf(
            "Mon Temps lit uniquement vos séances d'exercice (type, début, fin, titre), par exemple celles que Garmin " +
                "Connect copie depuis votre montre.",
            "Elles servent à vous proposer de les ajouter à votre historique, depuis Réglages → Montre Garmin → " +
                "Importer les séances sportives. Rien n'est ajouté sans votre choix.",
            "La lecture n'a lieu que lorsque vous ouvrez cet écran. Mon Temps n'écrit rien dans Health Connect, " +
                "ne lit aucune autre donnée de santé et ne transmet rien : l'app n'a pas accès à Internet.",
            "Vous pouvez retirer l'autorisation à tout moment dans Health Connect.",
        )
    }
}
