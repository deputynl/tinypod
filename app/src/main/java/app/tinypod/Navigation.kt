package app.tinypod

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.tinypod.ui.AddPodcastScreen
import app.tinypod.ui.DownloadsScreen
import app.tinypod.ui.FolderScreen
import app.tinypod.ui.FullPlayerScreen
import app.tinypod.ui.HistoryScreen
import app.tinypod.ui.LibraryScreen
import app.tinypod.ui.MiniPlayer
import app.tinypod.ui.NewEpisodesScreen
import app.tinypod.ui.PodcastScreen
import app.tinypod.ui.QueueScreen
import app.tinypod.ui.TabsViewModel

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(NewEpisodes)
  val vm: TabsViewModel = viewModel(factory = TabsViewModel.Factory)

  Scaffold(
    bottomBar = {
      Column {
      if (backStack.lastOrNull() != FullPlayer) MiniPlayer(onOpen = { backStack.add(FullPlayer) })
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
    }
  ) { padding ->
    NavDisplay(
      backStack = backStack,
      // No top padding: screens handle the status bar themselves, so tinted ones can colour it too.
      modifier = Modifier.padding(start = padding.calculateStartPadding(LocalLayoutDirection.current), end = padding.calculateEndPadding(LocalLayoutDirection.current), bottom = padding.calculateBottomPadding()),
      onBack = { backStack.removeLastOrNull() },
      // Gives each entry its own saved state and ViewModel scope, cleared when it leaves the stack.
      entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
      entryProvider =
        entryProvider {
          entry<NewEpisodes> { BelowStatusBar { NewEpisodesScreen(vm, onAddPodcast = { backStack.add(AddPodcast) }) } }
          entry<Library> {
            BelowStatusBar {
              LibraryScreen(
                vm,
                onPodcastClick = { backStack.add(PodcastDetail(it)) },
                onFolderClick = { backStack.add(FolderDetail(it)) },
                onAddPodcast = { backStack.add(AddPodcast) },
              )
            }
          }
          entry<AddPodcast> {
            BelowStatusBar {
              AddPodcastScreen(
                onSubscribed = { id ->
                  backStack.removeLastOrNull()
                  backStack.add(PodcastDetail(id))
                }
              )
            }
          }
          entry<PodcastDetail> { key -> PodcastScreen(key.podcastId, onUnsubscribed = { backStack.removeLastOrNull() }) }
          entry<FolderDetail> { key ->
            BelowStatusBar {
              FolderScreen(key.folderId, onPodcastClick = { backStack.add(PodcastDetail(it)) }, onDeleted = { backStack.removeLastOrNull() })
            }
          }
          entry<FullPlayer> { FullPlayerScreen() }
          entry<Queue> { BelowStatusBar { QueueScreen(vm) } }
          entry<History> { BelowStatusBar { HistoryScreen(vm) } }
          entry<Downloads> { BelowStatusBar { DownloadsScreen(vm) } }
        },
    )
  }
}

/** Keeps a screen's content clear of the status bar (tinted screens draw behind it themselves). */
@Composable
private fun BelowStatusBar(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize().statusBarsPadding()) { content() }
