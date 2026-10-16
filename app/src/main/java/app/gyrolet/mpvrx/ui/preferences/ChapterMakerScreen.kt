/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.batcheditor.BatchEditorBridge
import app.gyrolet.mpvrx.utils.batcheditor.BatchEditorFileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
object ChapterMakerScreen : Screen {

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()

    var folderUri by rememberSaveable { mutableStateOf("") }
    var folderName by rememberSaveable { mutableStateOf("") }
    var fileUri by rememberSaveable { mutableStateOf("") }
    var fileName by rememberSaveable { mutableStateOf("") }

    var chapterName by rememberSaveable { mutableStateOf("Intro") }
    var timingMode by rememberSaveable { mutableStateOf("first") }

    var minutes by rememberSaveable { mutableStateOf("0") }
    var seconds by rememberSaveable { mutableStateOf("0") }

    var startMinutes by rememberSaveable { mutableStateOf("0") }
    var startSeconds by rememberSaveable { mutableStateOf("0") }
    var endMinutes by rememberSaveable { mutableStateOf("0") }
    var endSeconds by rememberSaveable { mutableStateOf("0") }
    var endAtDuration by rememberSaveable { mutableStateOf(false) }

    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var showWipeConfirmation by remember { mutableStateOf(false) }

    val folderPicker =
      rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
      ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        try {
          context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
              Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
          )

          folderUri = uri.toString()
          folderName =
            DocumentFile
              .fromTreeUri(context, uri)
              ?.name
              ?: uri.lastPathSegment.orEmpty()

          fileUri = ""
          fileName = ""

          status = ""
          error = ""
        } catch (e: Exception) {
          error = e.localizedMessage ?: "Could not access folder"
        }
      }

    val filePicker =
      rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
      ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        try {
          context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
              Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
          )

          fileUri = uri.toString()
          fileName =
            DocumentFile
              .fromSingleUri(context, uri)
              ?.name
              ?: uri.lastPathSegment.orEmpty()

          folderUri = ""
          folderName = ""

          status = ""
          error = ""
        } catch (e: Exception) {
          error = e.localizedMessage ?: "Could not access file"
        }
      }

    fun parsePart(value: String): Int? {
      return value.toIntOrNull()?.takeIf { it >= 0 }
    }

    fun startWipeChapters() {
      if (
        folderUri.isBlank() &&
        fileUri.isBlank()
      ) {
        return
      }

      if (running) return
      showWipeConfirmation = false

      scope.launch {
        running = true
        status = context.getString(R.string.chapter_maker_processing)
        error = ""

        try {
          val files =
            if (fileUri.isNotBlank()) {
              val file =
                DocumentFile.fromSingleUri(
                  context,
                  Uri.parse(fileUri),
                )
                  ?: kotlin.error("Selected file is no longer available")

              listOf(file)
            } else {
              val root =
                DocumentFile.fromTreeUri(
                  context,
                  Uri.parse(folderUri),
                )
                  ?: kotlin.error("Selected folder is no longer available")

              withContext(Dispatchers.IO) {
                BatchEditorFileUtils.collectMatroskaVideos(root)
              }
            }

          if (files.isEmpty()) {
            error = context.getString(R.string.chapter_maker_no_videos)
            return@launch
          }

          var success = 0
          var failed = 0
          val failureDetails = mutableListOf<String>()

          files.forEachIndexed { index, file ->
            withContext(Dispatchers.Main.immediate) {
              status = context.getString(
                R.string.chapter_maker_progress,
                index + 1,
                files.size,
                file.name.orEmpty(),
              )
            }

            try {
              withContext(Dispatchers.IO) {
                BatchEditorFileUtils.withWritableFileDescriptor(
                  context.applicationContext,
                  file,
                ) { pfd ->
                  BatchEditorBridge.clearChapters(
                    context.applicationContext,
                    pfd,
                  )
                }
              }
              success++
            } catch (e: Exception) {
              failed++
              if (failureDetails.size < 5) {
                failureDetails +=
                  "${file.name.orEmpty()}: ${e.localizedMessage ?: "Unknown error"}"
              }
            }
          }

          status = context.getString(
            R.string.chapter_maker_result,
            success,
            failed,
          )

          if (failureDetails.isNotEmpty()) {
            status += "\n" + failureDetails.joinToString("\n")
            if (failed > failureDetails.size) {
              status += "\n… and ${failed - failureDetails.size} more failure(s)"
            }
          }
        } catch (e: Exception) {
          error = e.localizedMessage ?: "Chapter wiping failed"
        } finally {
          running = false
        }
      }
    }

    val timingValid =
      when (timingMode) {
        "first",
        "last",
        ->
          (parsePart(minutes) ?: -1) >= 0 &&
            (parsePart(seconds) ?: -1) in 0..59 &&
            ((parsePart(minutes) ?: 0) > 0 ||
              (parsePart(seconds) ?: 0) > 0)

        "range" -> {
          val startMin = parsePart(startMinutes)
          val startSec = parsePart(startSeconds)
          val endMin = parsePart(endMinutes)
          val endSec = parsePart(endSeconds)
          startMin != null &&
            startSec != null &&
            startSec in 0..59 &&
            (endAtDuration ||
              (endMin != null &&
                endSec != null &&
                endSec in 0..59 &&
                endMin * 60 + endSec > startMin * 60 + startSec))
        }

        else -> false
      }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = context.getString(R.string.chapter_maker_title),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            if (LocalShowSettingsBackArrow.current) {
              androidx.compose.material3.IconButton(
                onClick = { backStack.popSafely() },
              ) {
                Icon(
                  Icons.RoundedFilled.ArrowBack,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.secondary,
                )
              }
            }
          },
        )
      },
    ) { padding ->
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = context.getString(R.string.chapter_maker_summary),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
          text =
            when {
              fileName.isNotBlank() -> fileName
              folderName.isNotBlank() -> folderName
              else -> context.getString(R.string.batch_folder_not_selected)
            },
          style = MaterialTheme.typography.titleMedium,
        )

        OutlinedButton(
          onClick = { folderPicker.launch(null) },
          enabled = !running,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(
            Icons.RoundedFilled.Folder,
            contentDescription = null,
          )
          Spacer(Modifier.size(8.dp))
          Text(
            if (folderUri.isBlank()) {
              context.getString(R.string.chapter_maker_select_folder)
            } else {
              context.getString(R.string.chapter_maker_change_folder)
            },
          )
        }

        OutlinedButton(
          onClick = {
            filePicker.launch(
              arrayOf(
                "video/x-matroska",
                "video/webm",
                "video/*",
              ),
            )
          },
          enabled = !running,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(
            Icons.RoundedFilled.Folder,
            contentDescription = null,
          )
          Spacer(Modifier.size(8.dp))
          Text(
            if (fileUri.isBlank()) {
              context.getString(R.string.chapter_maker_select_file)
            } else {
              context.getString(R.string.chapter_maker_change_file)
            },
          )
        }

        OutlinedTextField(
          value = chapterName,
          onValueChange = { chapterName = it },
          modifier = Modifier.fillMaxWidth(),
          label = {
            Text(context.getString(R.string.chapter_maker_chapter_name))
          },
          singleLine = true,
          enabled = !running,
        )

        Text(
          text = context.getString(R.string.chapter_maker_timing),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )

        TimingOption(
          selected = timingMode == "first",
          text = context.getString(R.string.chapter_maker_first),
          onClick = { timingMode = "first" },
          enabled = !running,
        )

        if (timingMode == "first") {
          TimeInputRow(
            minutes = minutes,
            seconds = seconds,
            onMinutesChange = { minutes = it },
            onSecondsChange = { seconds = it },
            enabled = !running,
          )
        }

        TimingOption(
          selected = timingMode == "last",
          text = context.getString(R.string.chapter_maker_last),
          onClick = { timingMode = "last" },
          enabled = !running,
        )

        if (timingMode == "last") {
          TimeInputRow(
            minutes = minutes,
            seconds = seconds,
            onMinutesChange = { minutes = it },
            onSecondsChange = { seconds = it },
            enabled = !running,
          )
        }

        TimingOption(
          selected = timingMode == "range",
          text = context.getString(R.string.chapter_maker_specific_range),
          onClick = { timingMode = "range" },
          enabled = !running,
        )

        if (timingMode == "range") {
          Text(context.getString(R.string.chapter_maker_start))

          TimeInputRow(
            minutes = startMinutes,
            seconds = startSeconds,
            onMinutesChange = { startMinutes = it },
            onSecondsChange = { startSeconds = it },
            enabled = !running,
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(context.getString(R.string.chapter_maker_end))
            Checkbox(
              checked = endAtDuration,
              onCheckedChange = { endAtDuration = it },
              enabled = !running,
            )
            Text(context.getString(R.string.chapter_maker_end_of_file))
          }

          TimeInputRow(
            minutes = endMinutes,
            seconds = endSeconds,
            onMinutesChange = { endMinutes = it },
            onSecondsChange = { endSeconds = it },
            enabled = !running && !endAtDuration,
          )
        }

        Button(
          onClick = {
            scope.launch {
              running = true
              status = context.getString(R.string.chapter_maker_processing)
              error = ""

              try {
                if (chapterName.isBlank()) {
                  kotlin.error("Chapter name cannot be empty")
                  return@launch
                }

                val files =
                  if (fileUri.isNotBlank()) {
                    val file =
                      DocumentFile.fromSingleUri(
                        context,
                        Uri.parse(fileUri),
                      )
                        ?: kotlin.error("Selected file is no longer available")

                    listOf(file)
                  } else {
                    val root =
                      DocumentFile.fromTreeUri(
                        context,
                        Uri.parse(folderUri),
                      )
                        ?: kotlin.error("Selected folder is no longer available")

                    withContext(Dispatchers.IO) {
                      BatchEditorFileUtils.collectMatroskaVideos(root)
                    }
                  }

                if (files.isEmpty()) {
                  error =
                    context.getString(
                      R.string.chapter_maker_no_videos,
                    )
                  return@launch
                }

                val request =
                  BatchEditorBridge.ChapterRequest(
                    title = chapterName.trim(),
                    mode = timingMode,
                    minutes = parsePart(minutes) ?: 0,
                    seconds = parsePart(seconds) ?: 0,
                    startMinutes = parsePart(startMinutes) ?: 0,
                    startSeconds = parsePart(startSeconds) ?: 0,
                    endMinutes = parsePart(endMinutes) ?: 0,
                    endSeconds = parsePart(endSeconds) ?: 0,
                    endAtDuration = endAtDuration,
                  )

                var success = 0
                var failed = 0
                val failureDetails = mutableListOf<String>()

                files.forEachIndexed { index, file ->
                  withContext(Dispatchers.Main.immediate) {
                    status =
                      context.getString(
                        R.string.chapter_maker_progress,
                        index + 1,
                        files.size,
                        file.name.orEmpty(),
                      )
                  }

                  try {
                    withContext(Dispatchers.IO) {
                      BatchEditorFileUtils.withWritableFileDescriptor(
                        context.applicationContext,
                        file,
                      ) { pfd ->
                        BatchEditorBridge.editChapters(
                          context.applicationContext,
                          pfd,
                          request,
                        )
                      }
                    }
                    success++
                  } catch (e: Exception) {
                    failed++
                    if (failureDetails.size < 5) {
                      failureDetails += "${file.name.orEmpty()}: ${e.localizedMessage ?: "Unknown error"}"
                    }
                  }
                }

                status =
                  context.getString(
                    R.string.chapter_maker_result,
                    success,
                    failed,
                  )
                if (failureDetails.isNotEmpty()) {
                  status += "\n" + failureDetails.joinToString("\n")
                  if (failed > failureDetails.size) {
                    status += "\n… and ${failed - failureDetails.size} more failure(s)"
                  }
                }
              } catch (e: Exception) {
                error = e.localizedMessage ?: "Chapter editing failed"
              } finally {
                running = false
              }
            }
          },
          enabled =
            !running &&
              (folderUri.isNotBlank() || fileUri.isNotBlank()) &&
              chapterName.isNotBlank() &&
              timingValid,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(context.getString(R.string.chapter_maker_apply))
        }

        OutlinedButton(
          onClick = { showWipeConfirmation = true },
          enabled = !running && (folderUri.isNotBlank() || fileUri.isNotBlank()),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(context.getString(R.string.chapter_maker_wipe_chapters))
        }

        if (showWipeConfirmation) {
          AlertDialog(
            onDismissRequest = {
              if (!running) showWipeConfirmation = false
            },
            title = {
              Text(context.getString(R.string.chapter_maker_wipe_title))
            },
            text = {
              Text(context.getString(R.string.chapter_maker_wipe_message))
            },
            confirmButton = {
              TextButton(
                onClick = { startWipeChapters() },
                enabled = !running,
              ) {
                Text(context.getString(R.string.chapter_maker_wipe_confirm))
              }
            },
            dismissButton = {
              TextButton(
                onClick = { showWipeConfirmation = false },
                enabled = !running,
              ) {
                Text(context.getString(android.R.string.cancel))
              }
            },
          )
        }

        if (status.isNotBlank()) {
          Text(
            text = status,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        if (error.isNotBlank()) {
          Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    }
  }
}

@Composable
private fun TimingOption(
  selected: Boolean,
  text: String,
  onClick: () -> Unit,
  enabled: Boolean,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(
      selected = selected,
      onClick = onClick,
      enabled = enabled,
    )
    Text(text)
  }
}

@Composable
private fun TimeInputRow(
  minutes: String,
  seconds: String,
  onMinutesChange: (String) -> Unit,
  onSecondsChange: (String) -> Unit,
  enabled: Boolean,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    OutlinedTextField(
      value = minutes,
      onValueChange = {
        if (it.length <= 4 && it.all(Char::isDigit)) {
          onMinutesChange(it)
        }
      },
      modifier = Modifier.width(130.dp),
      label = { Text("min") },
      keyboardOptions =
        KeyboardOptions(
          keyboardType = KeyboardType.Number,
        ),
      singleLine = true,
      enabled = enabled,
    )

    OutlinedTextField(
      value = seconds,
      onValueChange = {
        if (it.length <= 2 && it.all(Char::isDigit)) {
          onSecondsChange(it)
        }
      },
      modifier = Modifier.width(130.dp),
      label = { Text("sec") },
      keyboardOptions =
        KeyboardOptions(
          keyboardType = KeyboardType.Number,
        ),
      singleLine = true,
      enabled = enabled,
    )
  }
}
