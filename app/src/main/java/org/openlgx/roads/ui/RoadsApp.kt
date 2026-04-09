package org.openlgx.roads.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.navigation.compose.hiltViewModel
import org.openlgx.roads.ui.diagnostics.DiagnosticsScreen
import org.openlgx.roads.ui.diagnostics.DiagnosticsViewModel
import org.openlgx.roads.ui.home.HomeScreen
import org.openlgx.roads.ui.home.HomeViewModel
import org.openlgx.roads.ui.navigation.Routes
import org.openlgx.roads.ui.settings.SettingsScreen
import org.openlgx.roads.ui.settings.SettingsViewModel
import org.openlgx.roads.ui.sessions.SessionDetailScreen
import org.openlgx.roads.ui.sessions.SessionDetailViewModel
import org.openlgx.roads.ui.sessions.SessionsListScreen
import org.openlgx.roads.ui.sessions.SessionsListViewModel

@Composable
fun RoadsApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.Home) {
        composable(Routes.Home) {
            val viewModel: HomeViewModel = hiltViewModel()
            HomeScreen(
                viewModel = viewModel,
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onOpenDiagnostics = { navController.navigate(Routes.Diagnostics) },
                onOpenSessions = { navController.navigate(Routes.SessionList) },
            )
        }

        composable(Routes.Settings) {
            val viewModel: SettingsViewModel = hiltViewModel()
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.navigateUp() },
            )
        }

        composable(Routes.Diagnostics) {
            val viewModel: DiagnosticsViewModel = hiltViewModel()
            DiagnosticsScreen(
                viewModel = viewModel,
                onBack = { navController.navigateUp() },
            )
        }

        composable(Routes.SessionList) {
            val viewModel: SessionsListViewModel = hiltViewModel()
            SessionsListScreen(
                viewModel = viewModel,
                onBack = { navController.navigateUp() },
                onOpenSession = { id -> navController.navigate(Routes.sessionDetail(id)) },
            )
        }

        composable(
            route = Routes.SessionDetail,
            arguments =
                listOf(
                    navArgument("sessionId") {
                        type = NavType.LongType
                    },
                ),
        ) {
            val viewModel: SessionDetailViewModel = hiltViewModel()
            SessionDetailScreen(
                viewModel = viewModel,
                onBack = { navController.navigateUp() },
            )
        }
    }
}
