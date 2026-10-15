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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardOptions
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
object SubtitleEditorScreen : Screen {

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()

    var folderUri by rememberSaveable { mutableStateOf("") }
    var folderName by rememberSaveable { mutableStateOf("") }

    var tracks by remember {
      mutableStateOf<List<BatchEditorBridge.SubtitleTrack>>(emptyList())
    }

    var selectedTrack by rememberSaveable { mutableStateOf(1) }
    var name by rememberSaveable { mutableStateOf("") }
    var defaultMode by rememberSaveable { mutableStateOf("keep") }
    var position by rememberSaveable { mutableStateOf("1") }

    var scanStatus by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTrack, tracks) {
      val track = tracks.getOrNull(selectedTrack - 1)
      if (track != null) {
        name = track.name
        position = selectedTrack.toString()
        defaultMode = "keep"
      }
    }

    suspend fun scanFolder(uri: Uri) {
      scanStatus = context.getString(R.string.subtitle_editor_scanning)
      error = ""

      try {
        val root =
          withContext(Dispatchers.IO) {
            DocumentFile.fromTreeUri(context, uri)
              ?: kotlin.error("Selected folder is no longer available")
          }

        val files =
          withContext(Dispatchers.IO) {
            BatchEditorFileUtils.collectMatroskaVideos(root)
          }

        if (files.isEmpty()) {
          tracks = emptyList()
          scanStatus =
            context.getString(
              R.string.subtitle_editor_no_videos,
            )
          return
        }

        val firstTracks =
          withContext(Dispatchers.IO) {
            BatchEditorFileUtils.withReadableFileDescriptor(
              context.applicationContext,
              files.first(),
            ) { pfd ->
              BatchEditorBridge.inspectSubtitleTracks(pfd)
            }
          }

        tracks = firstTracks
        selectedTrack = 1

        scanStatus =
          context.getString(
            R.string.subtitle_editor_scanned,
            files.first().name.orEmpty(),
            files.size,
          )
      } catch (e: Exception) {
        tracks = emptyList()
        error = e.localizedMessage ?: "Could not inspect subtitle tracks"
      }
    }

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

          scope.launch {
            scanFolder(uri)
          }
        } catch (e: Exception) {
          error = e.localizedMessage ?: "Could not access folder"
        }
      }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = context.getString(R.string.subtitle_editor_title),
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
          text = context.getString(R.string.subtitle_editor_summary),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
          text =
            folderName.ifBlank {
              context.getString(R.string.batch_folder_not_selected)
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
              context.getString(R.string.subtitle_editor_select_folder)
            } else {
              context.getString(R.string.subtitle_editor_change_folder)
            },
          )
        }

        if (scanStatus.isNotBlank()) {
          Text(
            text = scanStatus,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        if (tracks.isNotEmpty()) {
          Text(
            text = context.getString(R.string.subtitle_editor_tracks),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )

          tracks.forEach { track ->
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(
                selected = selectedTrack == track.index,
                onClick = { selectedTrack = track.index },
                enabled = !running,
              )

              Column {
                Text(
                  text =
                    context.getString(
                      R.string.subtitle_editor_track_number,
                      track.index,
                      track.name.ifBlank { context.getString(R.string.generic_unknown) },
                    ),
                  fontWeight = FontWeight.SemiBold,
                )

                Text(
                  text =
                    buildString {
                      if (track.language.isNotBlank()) {
                        append(track.language)
                      }
                      if (track.codec.isNotBlank()) {
                        if (isNotEmpty()) append(" • ")
                        append(track.codec)
                      }
                      if (track.isDefault) {
                        if (isNotEmpty()) append(" • ")
                        append(
                          context.getString(
                            R.string.subtitle_editor_default_label,
                          ),
                        )
                      }
                      if (track.isForced) {
                        if (isNotEmpty()) append(" • ")
                        append(
                          context.getString(
                            R.string.subtitle_editor_forced_label,
                          ),
                        )
                      }
                    },
                  color = MaterialTheme.colorScheme.outline,
                )
              }
            }
          }

          OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = {
              Text(context.getString(R.string.subtitle_editor_name))
            },
            singleLine = true,
            enabled = !running,
          )

          Text(
            text = context.getString(R.string.subtitle_editor_default),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )

          DefaultOption(
            selected = defaultMode == "keep",
            text = context.getString(R.string.subtitle_editor_keep_default),
            onClick = { defaultMode = "keep" },
            enabled = !running,
          )

          DefaultOption(
            selected = defaultMode == "default",
            text = context.getString(R.string.subtitle_editor_make_default),
            onClick = { defaultMode = "default" },
            enabled = !running,
          )

          DefaultOption(
            selected = defaultMode == "not_default",
            text = context.getString(R.string.subtitle_editor_remove_default),
            onClick = { defaultMode = "not_default" },
            enabled = !running,
          )

          OutlinedTextField(
            value = position,
            onValueChange = {
              if (it.length <= 2 && it.all(Char::isDigit)) {
                position = it
              }
            },
            modifier = Modifier.width(150.dp),
            label = {
              Text(context.getString(R.string.subtitle_editor_position))
            },
            keyboardOptions =
              KeyboardOptions(
                keyboardType = KeyboardType.Number,
              ),
            singleLine = true,
            enabled = !running,
          )

          Button(
            onClick = {
              scope.launch {
                running = true
                status = context.getString(R.string.subtitle_editor_processing)
                error = ""

                try {
                  val root =
                    withContext(Dispatchers.IO) {
                      DocumentFile.fromTreeUri(
                        context,
                        Uri.parse(folderUri),
                      )
                        ?: kotlin.error("Selected folder is no longer available")
                    }

                  val files =
                    withContext(Dispatchers.IO) {
                      BatchEditorFileUtils.collectMatroskaVideos(root)
                    }

                  val targetPosition =
                    position.toIntOrNull()
                      ?: throw IllegalArgumentException(
                        "Subtitle position must be a number",
                      )

                  if (targetPosition < 1 ||
                    targetPosition > tracks.size
                  ) {
                    throw IllegalArgumentException(
                      "Subtitle position must be between 1 and ${tracks.size}",
                    )
                  }

                  var success = 0
                  var failed = 0
                  val failureDetails = mutableListOf<String>()

                  val request =
                    BatchEditorBridge.SubtitleEditRequest(
                      subtitleIndex = selectedTrack,
                      name = name,
                      changeName = true,
                      defaultMode = defaultMode,
                      newPosition = targetPosition,
                    )

                  files.forEachIndexed { index, file ->
                    withContext(Dispatchers.Main.immediate) {
                      status =
                        context.getString(
                          R.string.subtitle_editor_progress,
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
                          BatchEditorBridge.editSubtitleTracks(
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
                      R.string.subtitle_editor_result,
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
                  error = e.localizedMessage ?: "Subtitle editing failed"
                } finally {
                  running = false
                }
              }
            },
            enabled =
              !running &&
                folderUri.isNotBlank() &&
                tracks.isNotEmpty() &&
                selectedTrack in 1..tracks.size,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(context.getString(R.string.subtitle_editor_apply))
          }
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
private fun DefaultOption(
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
