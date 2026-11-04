@file:OptIn(ExperimentalMaterial3Api::class)

package fr.montemps.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fr.montemps.ui.activities.ActivitiesScreen
import fr.montemps.ui.activities.ActivitiesViewModel
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
import java.time.LocalDate

private enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    TODAY("today", "Aujourd'hui", Icons.Filled.Home),
    HISTORY("history", "Historique", Icons.AutoMirrored.Filled.List),
    REPORTS("reports", "Bilans", Icons.Filled.DateRange),
    SETTINGS("settings", "Réglages", Icons.Filled.Settings),
}

/** Vrai lorsque la barre de navigation du bas gère déjà l'encart inférieur. */
private val LocalHasBottomBar = compositionLocalOf { false }

@Composable
fun MonTempsNavHost(startRoute: String = TopLevel.TODAY.route) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBottomBar = TopLevel.entries.any { it.route == route } || route == null
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevel.entries.forEach { tl ->
                        NavigationBarItem(
                            selected = route == tl.route,
                            onClick = {
                                nav.navigate(tl.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tl.icon, contentDescription = null) },
                            label = { Text(tl.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
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
                    )
                }
                composable(TopLevel.REPORTS.route) { ReportsScreen(containerViewModel { ReportsViewModel(it) }) }
                composable(TopLevel.SETTINGS.route) {
                    SettingsScreen(containerViewModel { SettingsViewModel(it) }, onManageActivities = { nav.navigate("activities") })
                }
                composable("activities") {
                    ActivitiesScreen(containerViewModel { ActivitiesViewModel(it) }, onBack = { nav.popBackStack() })
                }
                composable("session/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                    val id = e.arguments!!.getLong("id")
                    SessionEditScreen(containerViewModel("edit-$id") { SessionEditViewModel(it, id, null) }, onDone = { nav.popBackStack() })
                }
                composable("session/new/{day}", arguments = listOf(navArgument("day") { type = NavType.LongType })) { e ->
                    val day = LocalDate.ofEpochDay(e.arguments!!.getLong("day"))
                    SessionEditScreen(containerViewModel("new-$day") { SessionEditViewModel(it, null, day) }, onDone = { nav.popBackStack() })
                }
            }
        }
    }
}

/** Échafaudage commun : barre de titre, retour éventuel, snackbar et bouton flottant. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    actions: @Composable () -> Unit = {},
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
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                    }
                },
                actions = { actions() },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = fab,
        content = content,
    )
}
