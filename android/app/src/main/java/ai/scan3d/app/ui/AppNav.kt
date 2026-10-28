package ai.scan3d.app.ui

import ai.scan3d.app.Scan3DApp
import ai.scan3d.app.ui.screens.CaptureScreen
import ai.scan3d.app.ui.screens.HelpScreen
import ai.scan3d.app.ui.screens.HomeScreen
import ai.scan3d.app.ui.screens.LibraryScreen
import ai.scan3d.app.ui.screens.NewScanScreen
import ai.scan3d.app.ui.screens.ProcessingScreen
import ai.scan3d.app.ui.screens.ReviewScreen
import ai.scan3d.app.ui.screens.SettingsScreen
import ai.scan3d.app.ui.screens.SplashScreen
import ai.scan3d.app.ui.screens.ViewerScreen
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val NEW = "new"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val HELP = "help"
    fun capture(id: String) = "capture/$id"
    fun review(id: String) = "review/$id"
    fun processing(id: String) = "processing/$id"
    fun viewer(id: String) = "viewer/$id"
}

@Composable
fun AppNav(app: Scan3DApp, nav: NavHostController = rememberNavController()) {
    NavHost(
        navController = nav,
        startDestination = Routes.SPLASH,
        enterTransition = { slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { slideOutHorizontally(tween(240)) { it / 6 } + fadeOut(tween(240)) },
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.SPLASH) { inclusive = true } } })
        }
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(Routes.NEW) { NewScanScreen(nav) }
        composable("capture/{id}") { CaptureScreen(nav, it.arguments?.getString("id")!!) }
        composable("review/{id}") { ReviewScreen(nav, it.arguments?.getString("id")!!) }
        composable("processing/{id}") { ProcessingScreen(nav, it.arguments?.getString("id")!!) }
        composable("viewer/{id}") { ViewerScreen(nav, it.arguments?.getString("id")!!) }
        composable(Routes.LIBRARY) { LibraryScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.HELP) { HelpScreen(nav) }
    }
}
