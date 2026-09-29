package app.tinypod.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FolderViewModel(private val folderId: Long, app: TinypodApp) : ViewModel() {
  private val folderDao = app.database.folderDao()
  val folder = folderDao.observe(folderId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
  val folders = folderDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
  val podcasts =
    app.database.podcastDao().observeInFolder(folderId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  fun rename(name: String) {
    viewModelScope.launch { folder.value?.let { folderDao.update(it.copy(name = name.trim())) } }
  }

  suspend fun delete() {
    folder.value?.let { folderDao.delete(it) }
  }

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
  val scope = rememberCoroutineScope()
  var renaming by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }

  FolderContent(folder, podcasts, onPodcastClick, onRename = { renaming = true }, onDelete = { confirmDelete = true })

  val current = folder ?: return
  if (renaming) {
    FolderNameDialog(
      title = "Rename folder",
      confirm = "Rename",
      folders = folders,
      initial = current.name,
      editing = current,
      onConfirm = { vm.rename(it); renaming = false },
      onDismiss = { renaming = false },
    )
  }
  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text("Delete “${current.name}”?") },
      text = { Text("Its podcasts stay subscribed and move back to the main Library list.") },
      confirmButton = {
        TextButton(
          onClick = {
            confirmDelete = false
            scope.launch {
              vm.delete()
              onDeleted()
            }
          }
        ) {
          Text("Delete")
        }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
  }
}

@Composable
fun FolderContent(
  folder: Folder?,
  podcasts: List<Podcast>,
  onPodcastClick: (Long) -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxSize()) {
    if (folder != null) {
      Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
        Text(folder.name, style = MaterialTheme.typography.headlineSmall)
        Row {
          TextButton(onClick = onRename, contentPadding = PaddingValues(0.dp)) { Text("Rename") }
          TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Delete") }
        }
      }
    }
    if (podcasts.isEmpty()) {
      EmptyState("No podcasts in this folder.\nUse “Folder” on a podcast's page to file it here.")
    } else {
      LazyColumn(Modifier.fillMaxSize()) { items(podcasts, key = { it.id }) { PodcastRow(it, onPodcastClick) } }
    }
  }
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
    FolderContent(Folder(id = 1, name = "News"), listOf(samplePodcast), onPodcastClick = {}, onRename = {}, onDelete = {})
  }

@Preview(showBackground = true)
@Composable
private fun EmptyFolderPreview() =
  TinypodTheme { FolderContent(Folder(id = 1, name = "Comedy"), emptyList(), onPodcastClick = {}, onRename = {}, onDelete = {}) }

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
