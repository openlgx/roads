package org.openlgx.roads.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import org.openlgx.roads.ui.diagnostics.DiagnosticsScreen
import org.openlgx.roads.ui.diagnostics.DiagnosticsViewModel
import org.openlgx.roads.ui.home.HomeScreen
import org.openlgx.roads.ui.home.HomeViewModel
import org.openlgx.roads.ui.navigation.Routes
import org.openlgx.roads.ui.settings.SettingsScreen
import org.openlgx.roads.ui.settings.SettingsViewModel

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
    }
}
