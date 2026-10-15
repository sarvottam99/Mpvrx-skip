/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.utils.batcheditor

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile

object BatchEditorFileUtils {

  private val matroskaExtensions =
    setOf(
      "mkv",
      "webm",
    )

  fun isMatroskaVideo(
    file: DocumentFile,
  ): Boolean {
    val extension =
      file.name
        ?.substringAfterLast('.', "")
        ?.lowercase()
        .orEmpty()

    return extension in matroskaExtensions ||
      file.type.equals("video/x-matroska", ignoreCase = true) ||
      file.type.equals("video/webm", ignoreCase = true)
  }

  fun collectMatroskaVideos(
    root: DocumentFile,
  ): List<DocumentFile> {
    val result = mutableListOf<DocumentFile>()

    fun visit(
      directory: DocumentFile,
    ) {
      directory
        .listFiles()
        .sortedBy { it.name.orEmpty().lowercase() }
        .forEach { child ->
          when {
            child.isDirectory -> visit(child)
            child.isFile && isMatroskaVideo(child) -> result += child
          }
        }
    }

    visit(root)

    return result
  }

  fun <T> withWritableFileDescriptor(
    context: Context,
    file: DocumentFile,
    block: (ParcelFileDescriptor) -> T,
  ): T {
    val pfd =
      context.contentResolver.openFileDescriptor(
        file.uri,
        "rw",
      )
        ?: error(
          "Could not open ${file.name.orEmpty()} for read/write",
        )

    return pfd.use(block)
  }
}
