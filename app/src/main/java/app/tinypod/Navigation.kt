package app.tinypod

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.tinypod.ui.DownloadsScreen
import app.tinypod.ui.HistoryScreen
import app.tinypod.ui.LibraryScreen
import app.tinypod.ui.NewEpisodesScreen
import app.tinypod.ui.QueueScreen
import app.tinypod.ui.TabsViewModel

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(NewEpisodes)
  val vm: TabsViewModel = viewModel(factory = TabsViewModel.Factory)

  Scaffold(
    bottomBar = {
      NavigationBar {
        Tab.entries.forEach { tab ->
          NavigationBarItem(
            selected = backStack.firstOrNull() == tab.key,
            onClick = {
              // Tabs are top-level destinations: switching resets the stack to that tab.
              backStack.clear()
              backStack.add(tab.key)
            },
            icon = { Icon(tab.icon, contentDescription = null) },
            label = { Text(tab.label) },
          )
        }
      }
    }
  ) { padding ->
    NavDisplay(
      backStack = backStack,
      modifier = Modifier.padding(padding),
      onBack = { backStack.removeLastOrNull() },
      entryProvider =
        entryProvider {
          entry<NewEpisodes> { NewEpisodesScreen(vm) }
          entry<Library> { LibraryScreen(vm) }
          entry<Queue> { QueueScreen(vm) }
          entry<History> { HistoryScreen(vm) }
          entry<Downloads> { DownloadsScreen(vm) }
        },
    )
  }
}
