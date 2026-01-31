package com.shinil.agoralivedemo.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shinil.agoralivedemo.ui.call.CallScreen
import com.shinil.agoralivedemo.ui.home.HomeScreen
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Call : Screen("call/{username}") {
        fun createRoute(username: String): String {
            val encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8.toString())
            return "call/$encodedUsername"
        }
    }
}

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    permissionsGranted: Boolean = true,
    onRequestPermissions: () -> Unit = {}
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                modifier = modifier,
                permissionsGranted = permissionsGranted,
                onRequestPermissions = onRequestPermissions,
                onJoinCall = { username ->
                    navController.navigate(Screen.Call.createRoute(username))
                }
            )
        }

        composable(
            route = Screen.Call.route,
            arguments = listOf(
                navArgument("username") {
                    type = NavType.StringType
                    defaultValue = "User"
                }
            )
        ) {
            CallScreen(
                modifier = modifier,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
