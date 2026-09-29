package app.tinypod.ui

import app.tinypod.data.Folder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderNameTest {
  private val folders = listOf(Folder(id = 1, name = "News"), Folder(id = 2, name = "Comedy"))

  @Test
  fun `accepts a new name`() = assertNull(folderNameError("  Science ", folders))

  @Test
  fun `rejects blank names`() = assertEquals("Enter a name", folderNameError("   ", folders))

  @Test
  fun `rejects an existing name regardless of case and spacing`() =
    assertEquals("There's already a folder with this name", folderNameError(" news", folders))

  @Test
  fun `a folder may keep its own name when renamed`() = assertNull(folderNameError("NEWS", folders, editing = folders[0]))
}
