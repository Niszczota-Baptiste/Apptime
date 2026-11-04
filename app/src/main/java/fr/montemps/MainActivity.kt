package fr.montemps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import fr.montemps.ui.MonTempsNavHost
import fr.montemps.ui.theme.MonTempsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MonTempsTheme {
                MonTempsNavHost()
            }
        }
    }
}
