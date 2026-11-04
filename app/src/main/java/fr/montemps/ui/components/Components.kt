package fr.montemps.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.montemps.domain.model.TrackedActivity
import fr.montemps.domain.report.Fmt
import fr.montemps.domain.report.GoalProgress
import fr.montemps.domain.report.PeriodReport
import java.time.Duration

/** Chiffres à chasse fixe : un chronomètre qui ne « danse » pas. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

@Composable
fun activityColor(activity: TrackedActivity?): Color =
    activity?.let { Color(it.color) } ?: MaterialTheme.colorScheme.outline

/** Repère discret d'une activité : une pastille de sa couleur (aucune icône, par sobriété). */
@Composable
fun ActivityMarker(activity: TrackedActivity?, modifier: Modifier = Modifier, dotSize: Dp = 10.dp) {
    Box(modifier.widthIn(min = 20.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(dotSize).clip(CircleShape).background(activityColor(activity)))
    }
}

/**
 * Raccourci d'activité : un appui démarre, un appui long propose de démarrer plus tôt.
 * L'activité active est en négatif (fond encre, texte clair) et porte la mention « En cours » :
 * reconnaissable sans dépendre de la couleur.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActivityTile(
    activity: TrackedActivity,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (isActive) scheme.inverseSurface else scheme.surfaceContainer, label = "tile")
    val content by animateColorAsState(if (isActive) scheme.inverseOnSurface else scheme.onSurface, label = "tileContent")
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(container)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = if (isActive) null else "Démarrer",
                onLongClickLabel = if (onLongClick != null) "Démarrer plus tôt" else null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics {
                contentDescription = if (isActive) "${activity.name}, en cours" else activity.name
                if (isActive) stateDescription = "En cours"
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        CompositionLocalProvider(LocalContentColor provides content) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ActivityMarker(activity)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        activity.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isActive) {
                        Text("En cours", style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

/** Progression d'un objectif : « Coréen  20 min / 30 min » et une barre fine à la couleur de l'activité. */
@Composable
fun GoalRow(progress: GoalProgress, caption: String? = null) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${progress.activity.name} : ${Fmt.spoken(progress.done)} sur ${Fmt.spoken(progress.goal.target)}" +
                    (if (progress.reached) ", objectif atteint" else "") + (caption?.let { ", $it" } ?: "")
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActivityMarker(progress.activity)
            Spacer(Modifier.width(12.dp))
            Text(progress.activity.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${Fmt.short(progress.done)} / ${Fmt.short(progress.goal.target)}",
                style = MaterialTheme.typography.bodyMedium.tabular(),
                color = if (progress.reached) MaterialTheme.colorScheme.onSurface else muted,
            )
            if (progress.reached) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        DistributionBar(listOf(activityColor(progress.activity) to progress.ratio.toFloat().coerceIn(0f, 1f)), height = 4.dp)
        caption?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/**
 * Frise d'une journée : sessions colorées sur 24 h (durée réelle du jour), le reste en gris
 * (non suivi), un trait pour l'heure actuelle. Décorative : le détail est dans la liste.
 */
@Composable
fun DayTimeline(blocks: List<Pair<Color, ClosedFloatingPointRange<Float>>>, nowFraction: Float?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            drawRect(scheme.surfaceContainerHighest)
            blocks.forEach { (color, range) ->
                val x = size.width * range.start
                val w = (size.width * (range.endInclusive - range.start)).coerceAtLeast(1f)
                drawRect(color, topLeft = Offset(x, 0f), size = Size(w, size.height))
            }
            nowFraction?.let {
                val x = size.width * it.coerceIn(0f, 1f)
                drawRect(scheme.onSurface, topLeft = Offset(x - 1.dp.toPx(), 0f), size = Size(2.dp.toPx(), size.height))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0 h", "6 h", "12 h", "18 h", "24 h").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/** « +1 h 05 » / « −30 min » (différence de durée, signe explicite). */
fun signedShort(d: java.time.Duration): String = when {
    d.isZero -> "="
    d.isNegative -> "−${Fmt.short(d.abs())}"
    else -> "+${Fmt.short(d)}"
}

/** Bouton pilule : principal (encre) ou discret (gris clair). */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
        shape = CircleShape,
        colors = if (primary) {
            ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary)
        } else {
            ButtonDefaults.buttonColors(containerColor = scheme.surfaceContainer, contentColor = scheme.onSurface)
        },
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

/** Petit carré « stop ». */
@Composable
fun StopGlyph() {
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(LocalContentColor.current))
}

@Composable
fun AddFab(text: String, onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        text = { Text(text, style = MaterialTheme.typography.titleSmall) },
        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
        onClick = onClick,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
    )
}

/** Grand titre de page, dans le contenu (pas de barre d'application). */
@Composable
fun PageHeader(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
        actions()
    }
}

/** Intitulé de section discret, avec valeur facultative alignée à droite. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null, hint: String? = null) {
    Row(modifier.fillMaxWidth().padding(top = 28.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
        }
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.titleSmall.tabular(), color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Bandeau d'information sobre avec une action. */
@Composable
fun InfoBanner(text: String, action: String, onAction: () -> Unit, icon: ImageVector? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/** Encadré d'explication (texte secondaire). */
@Composable
fun NoteBox(text: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp), modifier = modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Champ cliquable centré (date, heure). */
@Composable
fun FieldChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.heightIn(min = 52.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge.tabular(),
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Champ de sélection pleine largeur (ex. activité). */
@Composable
fun SelectorField(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** Sélecteur segmenté minimal (Jour / Semaine). */
@Composable
fun <T> SegmentedToggle(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(scheme.surfaceContainer)
            .padding(4.dp)
            .selectableGroup(),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val bg by animateColorAsState(if (isSelected) scheme.surfaceBright else Color.Transparent, label = "segment")
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(bg)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(option) })
                    .heightIn(min = 40.dp)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) scheme.onSurface else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Barre de répartition fine : un segment arrondi par activité, proportionnel à sa durée.
 * La partie grise restante correspond au temps non suivi.
 */
@Composable
fun DistributionBar(segments: List<Pair<Color, Float>>, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.fillMaxWidth().height(height)) {
        val radius = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        val gap = 2.dp.toPx()
        var x = 0f
        segments.forEach { (color, fraction) ->
            val w = size.width * fraction.coerceIn(0f, 1f)
            if (w >= 1f && x < size.width) {
                val drawWidth = (if (w > gap * 2) w - gap else w).coerceAtMost(size.width - x)
                drawRoundRect(color, topLeft = Offset(x, 0f), size = Size(drawWidth, size.height), cornerRadius = radius)
            }
            x += w
        }
    }
}

/** Segments d'un bilan, rapportés à la durée écoulée de la période (le reste = non suivi). */
@Composable
fun distributionSegments(report: PeriodReport, activities: Map<String, TrackedActivity>): List<Pair<Color, Float>> {
    val base = (if (report.elapsed.isZero) report.tracked else report.elapsed).toMillis().toFloat()
    if (base <= 0f) return emptyList()
    val fallback = MaterialTheme.colorScheme.outline
    return report.totals.map { t ->
        (activities[t.activityId]?.let { Color(it.color) } ?: fallback) to t.duration.toMillis() / base
    }
}

/** Ligne « repère · nom · durée · % du temps suivi ». */
@Composable
fun ShareRow(activity: TrackedActivity?, name: String, duration: Duration, share: Double) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$name : ${Fmt.spoken(duration)}, ${Fmt.percent(share)} du temps suivi"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActivityMarker(activity)
        Spacer(Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(Fmt.short(duration), style = MaterialTheme.typography.bodyLarge.tabular(), fontWeight = FontWeight.Medium)
        Text(
            Fmt.percent(share),
            style = MaterialTheme.typography.bodyMedium.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 56.dp),
        )
    }
}
