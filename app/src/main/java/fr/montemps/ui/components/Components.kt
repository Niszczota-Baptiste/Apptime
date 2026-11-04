package fr.montemps.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.report.Fmt
import java.time.Duration

/** Pastille ronde colorée contenant l'icône (émoji) de l'activité. */
@Composable
fun ActivityBadge(activity: TrackedActivity?, size: Dp = 40.dp) {
    val color = activity?.let { Color(it.color) } ?: MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = activity?.icon ?: "•",
            style = if (size >= 48.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/**
 * Raccourci d'activité : un seul appui démarre le suivi. L'activité active se distingue par une
 * bordure épaisse, un fond teinté, une coche et le libellé « En cours » (pas seulement la couleur).
 */
@Composable
fun ActivityTile(activity: TrackedActivity, isActive: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = Color(activity.color)
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 76.dp)
            .semantics {
                role = Role.Button
                contentDescription = if (isActive) "${activity.name}, en cours" else "Démarrer ${activity.name}"
                stateDescription = if (isActive) "En cours" else ""
            },
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (isActive) BorderStroke(3.dp, color) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActivityBadge(activity)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    activity.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isActive) {
                    Text("En cours", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (isActive) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

data class BarEntry(val activity: TrackedActivity?, val label: String, val duration: Duration, val share: Double)

/** Liste lisible « activité — durée — % du temps suivi » avec barre proportionnelle. */
@Composable
fun DurationBars(entries: List<BarEntry>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        entries.forEach { e ->
            Column(
                Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "${e.label} : ${Fmt.spoken(e.duration)}, ${Fmt.percent(e.share)} du temps suivi"
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActivityBadge(e.activity, size = 28.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(e.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Text(Fmt.short(e.duration), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Text(Fmt.percent(e.share), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(e.share.toFloat().coerceIn(0f, 1f))
                            .clip(RoundedCornerShape(5.dp))
                            .background(e.activity?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

/** Barre empilée (répartition) d'une journée ; la partie grise correspond au temps non suivi. */
@Composable
fun StackedBar(segments: List<Pair<Color, Double>>, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        segments.filter { it.second > 0 }.forEach { (color, fraction) ->
            Box(
                Modifier
                    .weight(fraction.toFloat().coerceAtLeast(0.0001f))
                    .fillMaxSize()
                    .background(color),
            )
        }
        val rest = 1.0 - segments.sumOf { it.second }
        if (rest > 0.0001) Spacer(Modifier.weight(rest.toFloat()))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}
