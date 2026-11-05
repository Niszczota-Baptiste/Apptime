@file:OptIn(ExperimentalMaterial3Api::class)

package fr.montemps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fr.montemps.ui.activities.ActivitiesScreen
import fr.montemps.ui.activities.ActivitiesViewModel
import fr.montemps.ui.components.AppIcons
import fr.montemps.ui.history.HistoryScreen
import fr.montemps.ui.history.HistoryViewModel
import fr.montemps.ui.history.SessionEditScreen
import fr.montemps.ui.history.SessionEditViewModel
import fr.montemps.ui.reports.ReportsScreen
import fr.montemps.ui.reports.ReportsViewModel
import fr.montemps.ui.settings.SettingsScreen
import fr.montemps.ui.settings.SettingsViewModel
import fr.montemps.ui.today.TodayScreen
import fr.montemps.ui.today.TodayViewModel
import fr.montemps.ui.workouts.WorkoutImportScreen
import fr.montemps.ui.workouts.WorkoutImportViewModel
import java.time.Instant
import java.time.LocalDate

private enum class TopLevel(val route: String, val label: String, val icon: () -> ImageVector) {
    TODAY("today", "Aujourd'hui", { AppIcons.Timer }),
    HISTORY("history", "Historique", { AppIcons.History }),
    REPORTS("reports", "Bilans", { AppIcons.Chart }),
    SETTINGS("settings", "Réglages", { Icons.Outlined.Settings }),
}

/** Vrai lorsque la barre de navigation du bas gère déjà l'encart inférieur. */
private val LocalHasBottomBar = compositionLocalOf { false }

@Composable
fun MonTempsNavHost(
    startRoute: String = TopLevel.TODAY.route,
    destination: String? = null,
    onDestinationHandled: () -> Unit = {},
) {
    val nav = rememberNavController()
    LaunchedEffect(destination) {
        val target = TopLevel.entries.firstOrNull { it.route == destination } ?: return@LaunchedEffect
        nav.navigate(target.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        onDestinationHandled()
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBottomBar = TopLevel.entries.any { it.route == route } || route == null

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBottomBar) {
                BottomBar(route) { tl ->
                    nav.navigate(tl.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        },
    ) { outer ->
        CompositionLocalProvider(LocalHasBottomBar provides showBottomBar) {
            NavHost(nav, startDestination = startRoute, modifier = Modifier.padding(outer).consumeWindowInsets(outer)) {
                composable(TopLevel.TODAY.route) {
                    TodayScreen(
                        containerViewModel { TodayViewModel(it) },
                        onManageActivities = { nav.navigate("activities") },
                        onOpenHistory = { nav.navigate(TopLevel.HISTORY.route) { launchSingleTop = true } },
                    )
                }
                composable(TopLevel.HISTORY.route) {
                    HistoryScreen(
                        containerViewModel { HistoryViewModel(it) },
                        onEdit = { id -> nav.navigate("session/$id") },
                        onAdd = { date -> nav.navigate("session/new/${date.toEpochDay()}") },
                        onFillGap = { date, start, end ->
                            nav.navigate("session/new/${date.toEpochDay()}?start=${start.toEpochMilli()}&end=${end.toEpochMilli()}")
                        },
                    )
                }
                composable(TopLevel.REPORTS.route) { ReportsScreen(containerViewModel { ReportsViewModel(it) }) }
                composable(TopLevel.SETTINGS.route) {
                    SettingsScreen(
                        containerViewModel { SettingsViewModel(it) },
                        onManageActivities = { nav.navigate("activities") },
                        onImportWorkouts = { nav.navigate("workouts") },
                    )
                }
                composable("activities") {
                    ActivitiesScreen(containerViewModel { ActivitiesViewModel(it) }, onBack = { nav.popBackStack() })
                }
                composable("workouts") {
                    WorkoutImportScreen(containerViewModel { WorkoutImportViewModel(it) }, onBack = { nav.popBackStack() })
                }
                composable("session/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                    val id = e.arguments!!.getLong("id")
                    SessionEditScreen(containerViewModel("edit-$id") { SessionEditViewModel(it, id, null) }, onDone = { nav.popBackStack() })
                }
                composable(
                    "session/new/{day}?start={start}&end={end}",
                    arguments = listOf(
                        navArgument("day") { type = NavType.LongType },
                        navArgument("start") { type = NavType.LongType; defaultValue = -1L },
                        navArgument("end") { type = NavType.LongType; defaultValue = -1L },
                    ),
                ) { e ->
                    val args = e.arguments!!
                    val day = LocalDate.ofEpochDay(args.getLong("day"))
                    val start = args.getLong("start")
                    val end = args.getLong("end")
                    val prefill = if (start > 0 && end > start) Instant.ofEpochMilli(start) to Instant.ofEpochMilli(end) else null
                    SessionEditScreen(
                        containerViewModel("new-$day-$start-$end") { SessionEditViewModel(it, null, day, prefill) },
                        onDone = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}

/** Barre du bas épurée : filet fin, icônes au trait, onglet actif en encre et en gras. */
@Composable
private fun BottomBar(current: String?, onSelect: (TopLevel) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(scheme.background)) {
        HorizontalDivider(thickness = 0.5.dp, color = scheme.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp)
                .selectableGroup(),
        ) {
            TopLevel.entries.forEach { tl ->
                val selected = current == tl.route
                val color = if (selected) scheme.onSurface else scheme.onSurfaceVariant
                Column(
                    Modifier
                        .weight(1f)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tl) })
                        .padding(start = 3.dp, end = 3.dp, top = 10.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(tl.icon(), contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(4.dp))
                    val labelStyle = MaterialTheme.typography.labelSmall
                    Text(
                        tl.label,
                        style = labelStyle,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Grand texte sur écran étroit : le libellé rétrécit un peu plutôt que d'être coupé.
                        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = labelStyle.fontSize, stepSize = 0.5.sp),
                    )
                }
            }
        }
    }
}

/**
 * Échafaudage commun. Écrans principaux : pas de barre d'application (le titre fait partie du
 * contenu). Sous-écrans : barre compacte et transparente avec retour.
 */
@Composable
fun ScreenScaffold(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    actions: @Composable RowScope.() -> Unit = {},
    fab: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val insets = if (LocalHasBottomBar.current) {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    } else {
        WindowInsets.safeDrawing
    }
    Scaffold(
        contentWindowInsets = insets,
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = { Text(title.orEmpty(), style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour") }
                    },
                    actions = actions,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = fab,
        content = content,
    )
}
