package app.tinypod.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.tinypod.TinypodApp
import app.tinypod.data.Folder
import app.tinypod.data.Podcast
import app.tinypod.theme.TinypodTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class FolderViewModel(folderId: Long, app: TinypodApp) : ViewModel() {
  private val folderDao = app.database.folderDao()
  val folder = folderDao.observe(folderId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
  val folders = folderDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
  val podcasts =
    app.database.podcastDao().observeInFolder(folderId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
  val newCounts =
    app.database.episodeDao().observeNewCounts().map { list -> list.associate { it.podcastId to it.count } }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

  companion object {
    fun factory(folderId: Long) = viewModelFactory { initializer { FolderViewModel(folderId, this[APPLICATION_KEY] as TinypodApp) } }
  }
}

@Composable
fun FolderScreen(folderId: Long, onPodcastClick: (Long) -> Unit, onDeleted: () -> Unit) {
  val vm: FolderViewModel = viewModel(key = "folder-$folderId", factory = FolderViewModel.factory(folderId))
  val folder by vm.folder.collectAsStateWithLifecycle()
  val folders by vm.folders.collectAsStateWithLifecycle()
  val podcasts by vm.podcasts.collectAsStateWithLifecycle()
  val newCounts by vm.newCounts.collectAsStateWithLifecycle()
  var dialog by remember { mutableStateOf<LibraryDialog?>(null) }

  FolderContent(folder, podcasts, newCounts, onPodcastClick, onDialog = { dialog = it })
  LibraryDialogs(dialog, folders, onDialog = { dialog = it }, onFolderDeleted = onDeleted)
}

@Composable
fun FolderContent(
  folder: Folder?,
  podcasts: List<Podcast>,
  newCounts: Map<Long, Int>,
  onPodcastClick: (Long) -> Unit,
  onDialog: (LibraryDialog) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (folder == null) return
  PodcastGrid(
    folders = emptyList(),
    podcasts = podcasts,
    allPodcasts = podcasts,
    newCounts = newCounts,
    onPodcastClick = onPodcastClick,
    onFolderClick = {},
    onDialog = onDialog,
    modifier = modifier,
    bottomPadding = 16.dp,
    header = {
      Column {
        Text(folder.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        Row {
          TextButton(onClick = { onDialog(LibraryDialog.RenameFolder(folder)) }, contentPadding = PaddingValues(0.dp)) { Text("Rename") }
          TextButton(onClick = { onDialog(LibraryDialog.DeleteFolder(folder)) }, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Delete") }
        }
        if (podcasts.isEmpty()) {
          Text(
            "No podcasts in this folder yet. Long-press a podcast in the Library, or use the folder button on its page, to file it here.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
          )
        }
      }
    },
  )
}

/** Why [name] can't be used for a folder, or null if it can. [editing] is the folder being renamed, if any. */
internal fun folderNameError(name: String, folders: List<Folder>, editing: Folder? = null): String? {
  val trimmed = name.trim()
  return when {
    trimmed.isEmpty() -> "Enter a name"
    folders.any { it.id != editing?.id && it.name.equals(trimmed, ignoreCase = true) } -> "There's already a folder with this name"
    else -> null
  }
}

@Composable
fun FolderNameDialog(
  title: String,
  confirm: String,
  folders: List<Folder>,
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit,
  initial: String = "",
  editing: Folder? = null,
) {
  // Start with the old name selected, so typing replaces it.
  var field by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
  val name = field.text
  // Only complain about an empty name once something was typed, not as soon as the dialog opens.
  val error = folderNameError(name, folders, editing).takeUnless { name.isEmpty() }
  val canConfirm = folderNameError(name, folders, editing) == null
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      OutlinedTextField(
        value = field,
        onValueChange = { field = it },
        label = { Text("Name") },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
      )
    },
    confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = canConfirm) { Text(confirm) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/** Picks the folder for a podcast: none, an existing one, or a new one. */
@Composable
fun MoveToFolderDialog(
  folders: List<Folder>,
  currentFolderId: Long?,
  onPick: (Long?) -> Unit,
  onNewFolder: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Folder") },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        FolderOption("No folder", selected = currentFolderId == null) { onPick(null) }
        folders.forEach { FolderOption(it.name, selected = it.id == currentFolderId) { onPick(it.id) } }
        TextButton(onClick = onNewFolder) { Text("New folder…") }
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun FolderOption(name: String, selected: Boolean, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = null)
    Text(name, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
  }
}

// Previews

@Preview(showBackground = true)
@Composable
private fun FolderPreview() =
  TinypodTheme {
    FolderContent(Folder(id = 1, name = "News"), listOf(samplePodcast), newCounts = mapOf(1L to 2), onPodcastClick = {}, onDialog = {})
  }

@Preview(showBackground = true)
@Composable
private fun EmptyFolderPreview() =
  TinypodTheme { FolderContent(Folder(id = 1, name = "Comedy"), emptyList(), newCounts = emptyMap(), onPodcastClick = {}, onDialog = {}) }

@Preview
@Composable
private fun MoveToFolderPreview() =
  TinypodTheme {
    MoveToFolderDialog(
      folders = listOf(Folder(id = 1, name = "News"), Folder(id = 2, name = "Comedy")),
      currentFolderId = 2,
      onPick = {},
      onNewFolder = {},
      onDismiss = {},
    )
  }
