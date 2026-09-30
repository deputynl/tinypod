package app.tinypod.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.tinypod.data.EpisodeWithPodcast

/**
 * Drag-to-reorder for episode rows in a LazyColumn (items keyed by episode id). While a row is
 * dragged, [order] holds the order being built, so updates from the database don't reset it;
 * [end] hands it back to be saved.
 *
 * The finger's position is tracked on its own ([start] plus the drag so far), independent of the
 * layout; the dragged row is drawn at the finger ([translation]) wherever its slot currently is.
 * That keeps it under the finger however far it moves and whatever the heights of the rows passed.
 */
class ReorderState(private val listState: LazyListState, private val canSwapWith: (Any) -> Boolean) {
  /** The rows in their dragged order, while a drag is in progress. */
  var order by mutableStateOf<List<EpisodeWithPodcast>?>(null)
    private set

  var draggedId by mutableStateOf<Long?>(null)
    private set

  /** The finger's position, as the dragged row's center in list coordinates. */
  private var center by mutableFloatStateOf(0f)

  private fun itemInfo(key: Any?) = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

  fun start(rows: List<EpisodeWithPodcast>, id: Long) {
    val info = itemInfo(id) ?: return
    order = rows
    draggedId = id
    center = info.offset + info.size / 2f
  }

  fun drag(dy: Float) {
    center += dy
    val list = order ?: return
    val from = list.indexOfFirst { it.episode.id == draggedId }
    // Swap with a neighbouring row once the finger passes its middle (so rows don't flip back and forth).
    val target =
      listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
        if (item.key == draggedId || !canSwapWith(item.key)) return@firstOrNull false
        val middle = item.offset + item.size / 2f
        val index = list.indexOfFirst { it.episode.id == item.key }
        (index < from && center < middle && center >= item.offset) || (index > from && center > middle && center < item.offset + item.size)
      } ?: return
    val to = list.indexOfFirst { it.episode.id == target.key }
    order = list.toMutableList().apply { add(to, removeAt(from)) }
  }

  /** Where to draw the dragged row relative to its current slot: under the finger. */
  fun translation(): Float {
    val info = itemInfo(draggedId) ?: return 0f
    return center - (info.offset + info.size / 2f)
  }

  /** Ends the drag and returns the new order (null if nothing was being dragged). */
  fun end(): List<EpisodeWithPodcast>? {
    val result = order
    order = null
    draggedId = null
    return result
  }
}

@Composable
fun rememberReorderState(listState: LazyListState, canSwapWith: (Any) -> Boolean): ReorderState =
  remember(listState) { ReorderState(listState, canSwapWith) }
