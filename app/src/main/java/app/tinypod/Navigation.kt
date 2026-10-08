package app.tinypod

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.tinypod.ui.AddPodcastScreen
import app.tinypod.ui.DownloadsScreen
import app.tinypod.ui.EpisodeScreen
import app.tinypod.ui.LocalEpisodeNavigator
import app.tinypod.ui.FolderScreen
import app.tinypod.ui.FullPlayerScreen
import app.tinypod.ui.HistoryScreen
import app.tinypod.ui.LibraryScreen
import app.tinypod.ui.MiniPlayer
import app.tinypod.ui.NewEpisodesScreen
import app.tinypod.ui.PodcastScreen
import app.tinypod.ui.QueueScreen
import app.tinypod.ui.SettingsScreen
import app.tinypod.ui.TabsViewModel

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(NewEpisodes)
  val vm: TabsViewModel = viewModel(factory = TabsViewModel.Factory)

  // A bottom bar on narrow windows, a side rail from 600 dp wide (unfolded foldables, tablets, and
  // landscape phones, where height is scarcest), switching live when the window changes.
  val wide = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
  val layoutType = if (wide) NavigationSuiteType.NavigationRail else NavigationSuiteType.NavigationBar
  NavigationSuiteScaffold(
    layoutType = layoutType,
    navigationSuiteItems = {
      Tab.entries.forEach { tab ->
        item(
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
    },
  ) {
    // Without a bottom bar, the content reaches the bottom edge: keep it clear of the gesture bar.
    val bottomInset =
      if (layoutType == NavigationSuiteType.NavigationBar) Modifier
      else Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
    // No top padding: screens handle the status bar themselves, so tinted ones can colour it too.
    Column(Modifier.fillMaxSize().then(bottomInset)) {
      // Tapping an episode anywhere opens its page.
      CompositionLocalProvider(LocalEpisodeNavigator provides { id -> backStack.add(EpisodeDetail(id)) }) {
        NavDisplay(
          backStack = backStack,
          modifier = Modifier.weight(1f),
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
                    onSettings = { backStack.add(SettingsPage) },
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
              entry<EpisodeDetail> { key -> EpisodeScreen(key.episodeId, onPodcastClick = { backStack.add(PodcastDetail(it)) }) }
              entry<FullPlayer> { FullPlayerScreen() }
              entry<SettingsPage> { BelowStatusBar { SettingsScreen() } }
              entry<Queue> { BelowStatusBar { QueueScreen(vm) } }
              entry<History> { BelowStatusBar { HistoryScreen(vm) } }
              entry<Downloads> { BelowStatusBar { DownloadsScreen(vm) } }
            },
        )
      }
      if (backStack.lastOrNull() != FullPlayer) MiniPlayer(onOpen = { backStack.add(FullPlayer) })
    }
  }
}

/** Keeps a screen's content clear of the status bar (tinted screens draw behind it themselves). */
@Composable
private fun BelowStatusBar(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize().statusBarsPadding()) { content() }
