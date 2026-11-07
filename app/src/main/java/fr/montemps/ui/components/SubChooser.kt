package fr.montemps.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.montemps.domain.model.SubActivity
import fr.montemps.domain.model.TrackedActivity

/**
 * Choix de la sous-catégorie au toucher d'une activité (Travail → Perso / Pro…). Sobre : une
 * ligne par choix, la sous-catégorie en cours marquée « En cours ». « Sans précision » démarre
 * l'activité sans sous-catégorie (proposé seulement si l'activité n'est pas déjà en cours).
 */
@Composable
fun SubChooserDialog(
    activity: TrackedActivity,
    subs: List<SubActivity>,
    title: String = activity.name,
    /** Sous-catégorie de la session en cours, si c'est cette activité. */
    currentSubId: String? = null,
    activityIsActive: Boolean = false,
    onPick: (subId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActivityMarker(activity, dotSize = 12.dp)
                Spacer(Modifier.width(10.dp))
                Text(title)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                subs.forEach { sub ->
                    val current = activityIsActive && sub.id == currentSubId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { onPick(sub.id) }
                            .semantics { if (current) stateDescription = "En cours" }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Couleur du texte principal : les choix se distinguent de « Sans précision ».
                        Text(
                            sub.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            color = scheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (current) Text("En cours", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    }
                }
                if (!activityIsActive) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { onPick(null) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Sans précision", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
