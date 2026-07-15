package com.example.livetranslate.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.livetranslate.di.AppContainer
import com.example.livetranslate.ui.history.HistoryDetailScreen
import com.example.livetranslate.ui.history.HistoryScreen
import com.example.livetranslate.ui.history.HistoryViewModel
import com.example.livetranslate.ui.live.LiveTranslateScreen
import com.example.livetranslate.ui.live.LiveTranslateViewModel
import com.example.livetranslate.ui.settings.SettingsScreen
import com.example.livetranslate.ui.settings.SettingsViewModel

object Routes {
    const val LIVE = "live"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val HISTORY_DETAIL = "history/{id}"
    fun historyDetail(id: Long) = "history/$id"
}

@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    val liveFactory = remember(container) { LiveTranslateViewModel.Factory(container) }
    val settingsFactory = remember(container) { SettingsViewModel.Factory(container) }
    val historyFactory = remember(container) { HistoryViewModel.Factory(container) }

    NavHost(navController = nav, startDestination = Routes.LIVE) {
        composable(Routes.LIVE) {
            val vm: LiveTranslateViewModel = viewModel(factory = liveFactory)
            LiveTranslateScreen(
                viewModel = vm,
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenHistory = { nav.navigate(Routes.HISTORY) }
            )
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = settingsFactory)
            SettingsScreen(
                viewModel = vm,
                onBack = { nav.popBackStack() }
            )
        }
        composable(Routes.HISTORY) {
            val vm: HistoryViewModel = viewModel(factory = historyFactory)
            HistoryScreen(
                viewModel = vm,
                onBack = { nav.popBackStack() },
                onOpenDetail = { id -> nav.navigate(Routes.historyDetail(id)) }
            )
        }
        composable(
            route = Routes.HISTORY_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            val vm: HistoryViewModel = viewModel(factory = historyFactory)
            HistoryDetailScreen(
                sessionId = id,
                viewModel = vm,
                onBack = { nav.popBackStack() },
                onOpenSession = { newId ->
                    nav.navigate(Routes.historyDetail(newId)) {
                        popUpTo(Routes.historyDetail(id)) { inclusive = true }
                    }
                }
            )
        }
    }
}
