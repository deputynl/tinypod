package app.tinypod.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.tinypod.TinypodApp
import app.tinypod.data.BackupFormatException
import app.tinypod.data.ImportResult
import app.tinypod.data.Settings
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val app: TinypodApp) : ViewModel() {
  val settings = app.settings

  private val _busy = MutableStateFlow(false)

  /** Whether an export or import is running. */
  val busy = _busy.asStateFlow()

  private val _message = MutableStateFlow<String?>(null)

  /** The outcome of the last export or import, shown once. */
  val message = _message.asStateFlow()

  fun messageShown() {
    _message.value = null
  }

  fun export(uri: Uri) =
    run("Couldn't save the backup") {
      withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri) }?.use { app.backup.export(it) } ?: error("No output")
      "Backup saved"
    }

  fun import(uri: Uri) =
    run("Couldn't read the backup") {
      val result = withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri) }?.use { app.backup.import(it) } ?: error("No input")
      importSummary(result)
    }

  /** Runs in the app's scope, so leaving the screen doesn't stop an import halfway. */
  private fun run(failure: String, block: suspend () -> String) {
    if (_busy.value) return
    _busy.value = true
    app.scope.launch {
      _message.value =
        try {
          block()
        } catch (e: BackupFormatException) {
          e.message
        } catch (e: Exception) {
          failure
        } finally {
          _busy.value = false
        }
    }
  }

  companion object {
    val Factory = viewModelFactory { initializer { SettingsViewModel(this[APPLICATION_KEY] as TinypodApp) } }
  }
}

internal fun importSummary(r: ImportResult): String {
  val podcasts = if (r.podcasts == 1) "1 podcast" else "${r.podcasts} podcasts"
  val failed =
    when (r.failed) {
      0 -> null
      1 -> "1 feed couldn't be fetched"
      else -> "${r.failed} feeds couldn't be fetched"
    }
  return listOfNotNull("Imported $podcasts", failed).joinToString(" · ")
}

@Composable
fun SettingsScreen() {
  val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
  val settings = vm.settings
  val clearOlder by settings.clearOlderOnFinish.collectAsStateWithLifecycle()
  val continueWithNext by settings.continueWithNext.collectAsStateWithLifecycle()
  val rewind by settings.rewindOnResumeSec.collectAsStateWithLifecycle()
  val busy by vm.busy.collectAsStateWithLifecycle()
  val message by vm.message.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  var pickRewind by remember { mutableStateOf(false) }

  val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::export) }
  val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }

  LaunchedEffect(message) {
    message?.let {
      snackbar.showSnackbar(it)
      vm.messageShown() // only now: clearing it first would cancel this effect
    }
  }

  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
      Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))

      SettingsSection("Playback")
      SwitchItem(
        "Continue with the next episode",
        "When the queue runs out, play the podcast's next newer unplayed episode",
        checked = continueWithNext,
        onChange = settings::setContinueWithNext,
      )
      ListItem(
        headlineContent = { Text("Rewind on resume") },
        supportingContent = {
          Text(if (rewind == 0) "Off" else "Go back $rewind seconds when playing again after a pause of a minute or more")
        },
        modifier = Modifier.clickable { pickRewind = true },
      )

      SettingsSection("New episodes")
      SwitchItem(
        "Finishing an episode clears older ones",
        "Finishing or marking an episode played makes the podcast's older episodes no longer new. Changing this only affects episodes you finish from now on.",
        checked = clearOlder,
        onChange = settings::setClearOlderOnFinish,
      )

      SettingsSection("Backup")
      ListItem(
        headlineContent = { Text("Export") },
        supportingContent = { Text("Save subscriptions, folders, progress, the queue and settings to a file. Downloads aren't included.") },
        modifier = Modifier.clickable(enabled = !busy) { exporter.launch("tinypod-backup-${LocalDate.now()}.json") },
      )
      ListItem(
        headlineContent = { Text("Import") },
        supportingContent = { Text("Restore from a backup file. It adds to what's here: nothing is removed, and its playback progress wins.") },
        trailingContent = if (busy) ({ CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp) }) else null,
        modifier = Modifier.clickable(enabled = !busy) { importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
      )
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
  }

  if (pickRewind) {
    RewindDialog(rewind, onPick = { settings.setRewindOnResumeSec(it); pickRewind = false }, onDismiss = { pickRewind = false })
  }
}

@Composable
private fun SettingsSection(title: String) {
  Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun SwitchItem(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  ListItem(
    headlineContent = { Text(title) },
    supportingContent = { Text(description) },
    trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    modifier = Modifier.clickable(role = Role.Switch) { onChange(!checked) },
  )
}

@Composable
private fun RewindDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Rewind on resume") },
    text = {
      Column {
        Settings.REWIND_CHOICES.forEach { seconds ->
          Row(
            Modifier.fillMaxWidth().selectable(selected = seconds == current, role = Role.RadioButton) { onPick(seconds) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(selected = seconds == current, onClick = null, modifier = Modifier.padding(end = 12.dp))
            Text(if (seconds == 0) "Off" else "$seconds seconds")
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
