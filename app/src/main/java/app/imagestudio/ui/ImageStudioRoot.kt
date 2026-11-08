package app.imagestudio.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import app.imagestudio.ui.create.CreateScreen
import app.imagestudio.ui.create.CreateViewModel
import app.imagestudio.ui.gallery.GalleryScreen
import app.imagestudio.ui.settings.SettingsScreen

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Create("create", "Create", Icons.Filled.AutoAwesome),
    Gallery("gallery", "Gallery", Icons.Filled.PhotoLibrary),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

@Composable
fun ImageStudioRoot() {
    val nav = rememberNavController()
    // Activity-scoped so the gallery can send an image back to the Create tab.
    val createVm: CreateViewModel = viewModel()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route

    fun go(route: String) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = { go(t.route) },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Create.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Create.route) { CreateScreen(createVm, onOpenSettings = { go(Tab.Settings.route) }) }
            composable(Tab.Gallery.route) {
                GalleryScreen(onRemix = { item, mode -> createVm.useAsSource(item, mode); go(Tab.Create.route) })
            }
            composable(Tab.Settings.route) { SettingsScreen() }
        }
    }
}
