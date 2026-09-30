package app.tinypod.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tinypod.TinypodApp
import app.tinypod.data.Folder
import app.tinypod.data.Podcast
import app.tinypod.theme.TinypodTheme
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(vm: TabsViewModel, onPodcastClick: (Long) -> Unit, onFolderClick: (Long) -> Unit, onAddPodcast: () -> Unit) {
  val folders by vm.folders.collectAsStateWithLifecycle()
  val podcasts by vm.podcasts.collectAsStateWithLifecycle()
  val newCounts by vm.newCounts.collectAsStateWithLifecycle()
  var dialog by remember { mutableStateOf<LibraryDialog?>(null) }

  Box(Modifier.fillMaxSize()) {
    if (folders.isEmpty() && podcasts.isEmpty()) {
      EmptyState("No podcasts yet.\nTap + to add one.")
    } else {
      PodcastGrid(
        folders = folders,
        podcasts = podcasts.filter { it.folderId == null },
        allPodcasts = podcasts,
        newCounts = newCounts,
        onPodcastClick = onPodcastClick,
        onFolderClick = onFolderClick,
        onDialog = { dialog = it },
      )
    }
    // Stacked in the corner: they only ever cover the rightmost column, and stay within thumb reach.
    Column(
      Modifier.align(Alignment.BottomEnd).padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      SmallFloatingActionButton(onClick = { dialog = LibraryDialog.NewFolder(forPodcast = null) }) {
        Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
      }
      FloatingActionButton(onClick = onAddPodcast) { Icon(Icons.Filled.Add, contentDescription = "Add podcast") }
    }
  }
  LibraryDialogs(dialog, folders, onDialog = { dialog = it })
}

/** Leaves room below the last row for the floating buttons (56 + 40 dp, plus spacing). */
private val GRID_BOTTOM_PADDING = 160.dp

/**
 * Podcasts as artwork tiles, preceded by [folders] as mosaic tiles, in as many columns as fit.
 * Long-pressing a tile opens its menu, whose choices come back through [onDialog].
 */
@Composable
fun PodcastGrid(
  folders: List<Folder>,
  podcasts: List<Podcast>,
  allPodcasts: List<Podcast>,
  newCounts: Map<Long, Int>,
  onPodcastClick: (Long) -> Unit,
  onFolderClick: (Long) -> Unit,
  onDialog: (LibraryDialog) -> Unit,
  modifier: Modifier = Modifier,
  /** Shown across the full width above the tiles, scrolling with them. */
  header: (@Composable () -> Unit)? = null,
  bottomPadding: Dp = GRID_BOTTOM_PADDING,
) {
  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 110.dp),
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    if (header != null) item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
    // Section labels only when there's both kinds, to tell them apart.
    val labelled = folders.isNotEmpty() && podcasts.isNotEmpty()
    if (labelled) item(key = "folders-label", span = { GridItemSpan(maxLineSpan) }) { GridLabel("Folders") }
    items(folders, key = { "f${it.id}" }) { folder ->
      val inside = allPodcasts.filter { it.folderId == folder.id }
      FolderTile(folder, inside, newCount = inside.sumOf { newCounts[it.id] ?: 0 }, onClick = { onFolderClick(folder.id) }, onDialog = onDialog)
    }
    if (labelled) item(key = "podcasts-label", span = { GridItemSpan(maxLineSpan) }) { GridLabel("Podcasts") }
    items(podcasts, key = { "p${it.id}" }) { podcast ->
      PodcastTile(podcast, newCount = newCounts[podcast.id] ?: 0, onClick = { onPodcastClick(podcast.id) }, onDialog = onDialog)
    }
  }
}

@Composable
private fun GridLabel(text: String) {
  Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PodcastTile(podcast: Podcast, newCount: Int, onClick: () -> Unit, onDialog: (LibraryDialog) -> Unit) {
  var menuOpen by remember { mutableStateOf(false) }
  val haptics = LocalHapticFeedback.current
  Box {
    Column(
      Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(
        onClick = onClick,
        onLongClickLabel = "Podcast options",
        onLongClick = {
          haptics.performHapticFeedback(HapticFeedbackType.LongPress)
          menuOpen = true
        },
      )
    ) {
      Box {
        Artwork(podcast.artworkUrl, Modifier.fillMaxWidth().aspectRatio(1f))
        NewBadge(newCount)
      }
      TileName(podcast.title)
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
      DropdownMenuItem(text = { Text("Move to folder…") }, onClick = { menuOpen = false; onDialog(LibraryDialog.MovePodcast(podcast)) })
      DropdownMenuItem(text = { Text("Unsubscribe") }, onClick = { menuOpen = false; onDialog(LibraryDialog.Unsubscribe(podcast)) })
    }
  }
}

/** A folder as a 2×2 mosaic of the artwork inside it, like folders on a home screen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(folder: Folder, inside: List<Podcast>, newCount: Int, onClick: () -> Unit, onDialog: (LibraryDialog) -> Unit) {
  var menuOpen by remember { mutableStateOf(false) }
  val haptics = LocalHapticFeedback.current
  Box {
    Column(
      Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(
        onClick = onClick,
        onLongClickLabel = "Folder options",
        onLongClick = {
          haptics.performHapticFeedback(HapticFeedbackType.LongPress)
          menuOpen = true
        },
      )
    ) {
      Box {
        Box(
          Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(8.dp),
          contentAlignment = Alignment.Center,
        ) {
          if (inside.isEmpty()) {
            Icon(Icons.Filled.Folder, contentDescription = null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
          } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              for (row in 0 until 2) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                  for (col in 0 until 2) {
                    val podcast = inside.getOrNull(row * 2 + col)
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                      if (podcast != null) Artwork(podcast.artworkUrl, Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)))
                    }
                  }
                }
              }
            }
          }
        }
        NewBadge(newCount)
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Folder, contentDescription = null, Modifier.padding(top = 6.dp, end = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        TileName(folder.name, maxLines = 1)
      }
      Text(
        if (inside.size == 1) "1 podcast" else "${inside.size} podcasts",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
      DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onDialog(LibraryDialog.RenameFolder(folder)) })
      DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDialog(LibraryDialog.DeleteFolder(folder)) })
    }
  }
}

@Composable
private fun TileName(text: String, maxLines: Int = 2) {
  Text(text, style = MaterialTheme.typography.labelLarge, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
}

/** The number of new episodes (as on the New tab), in the tile's top corner. */
@Composable
private fun BoxScope.NewBadge(count: Int) {
  if (count > 0) {
    Badge(Modifier.align(Alignment.TopEnd).padding(6.dp)) { Text(if (count > 99) "99+" else "$count", Modifier.padding(horizontal = 2.dp)) }
  }
}

/** A dialog opened from the library, folder or tile menus. */
sealed interface LibraryDialog {
  /** Creates a folder; with [forPodcast], files that podcast in it too. */
  data class NewFolder(val forPodcast: Podcast?) : LibraryDialog

  data class MovePodcast(val podcast: Podcast) : LibraryDialog

  data class Unsubscribe(val podcast: Podcast) : LibraryDialog

  data class RenameFolder(val folder: Folder) : LibraryDialog

  data class DeleteFolder(val folder: Folder) : LibraryDialog
}

/** Shows [dialog] and carries out its choice. [onFolderDeleted] runs after a folder is deleted. */
@Composable
fun LibraryDialogs(dialog: LibraryDialog?, folders: List<Folder>, onDialog: (LibraryDialog?) -> Unit, onFolderDeleted: () -> Unit = {}) {
  val actions = (LocalContext.current.applicationContext as TinypodApp).libraryActions
  val scope = rememberCoroutineScope()
  val close = { onDialog(null) }
  fun run(block: suspend () -> Unit) {
    close()
    scope.launch { block() }
  }
  when (dialog) {
    null -> {}
    is LibraryDialog.NewFolder ->
      FolderNameDialog(
        title = "New folder",
        confirm = "Create",
        folders = folders,
        onConfirm = { name ->
          val podcast = dialog.forPodcast
          run { if (podcast != null) actions.moveToNewFolder(podcast.id, name) else actions.createFolder(name) }
        },
        onDismiss = close,
      )
    is LibraryDialog.MovePodcast ->
      MoveToFolderDialog(
        folders = folders,
        currentFolderId = dialog.podcast.folderId,
        onPick = { folderId -> run { actions.moveToFolder(dialog.podcast.id, folderId) } },
        onNewFolder = { onDialog(LibraryDialog.NewFolder(forPodcast = dialog.podcast)) },
        onDismiss = close,
      )
    is LibraryDialog.Unsubscribe ->
      AlertDialog(
        onDismissRequest = close,
        title = { Text("Unsubscribe from ${dialog.podcast.title}?") },
        text = { Text("This removes the podcast and all its episodes, including playback progress and downloads.") },
        confirmButton = { TextButton(onClick = { run { actions.unsubscribe(dialog.podcast) } }) { Text("Unsubscribe") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
      )
    is LibraryDialog.RenameFolder ->
      FolderNameDialog(
        title = "Rename folder",
        confirm = "Rename",
        folders = folders,
        initial = dialog.folder.name,
        editing = dialog.folder,
        onConfirm = { name -> run { actions.renameFolder(dialog.folder, name) } },
        onDismiss = close,
      )
    is LibraryDialog.DeleteFolder ->
      AlertDialog(
        onDismissRequest = close,
        title = { Text("Delete “${dialog.folder.name}”?") },
        text = { Text("Its podcasts stay subscribed and move back to the main Library list.") },
        confirmButton = {
          TextButton(
            onClick = {
              run {
                actions.deleteFolder(dialog.folder)
                onFolderDeleted()
              }
            }
          ) {
            Text("Delete")
          }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
      )
  }
}

// Previews

@Preview(showBackground = true)
@Composable
private fun LibraryGridPreview() =
  TinypodTheme {
    val inFolder = listOf(samplePodcast.copy(id = 3, folderId = 1), samplePodcast.copy(id = 4, folderId = 1), samplePodcast.copy(id = 5, folderId = 1))
    PodcastGrid(
      folders = listOf(Folder(id = 1, name = "News"), Folder(id = 2, name = "Comedy")),
      podcasts = listOf(samplePodcast, samplePodcast.copy(id = 2, title = "A Show With A Rather Long Name")),
      allPodcasts = inFolder,
      newCounts = mapOf(1L to 3, 3L to 1, 4L to 2),
      onPodcastClick = {},
      onFolderClick = {},
      onDialog = {},
    )
  }
