/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.components.PreferenceCard
import app.gyrolet.mpvrx.ui.preferences.components.PreferenceSectionHeader
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.Preference

@Serializable
object BatchEditorScreen : Screen {

  @Composable
  override fun Content() {
    val backStack = LocalBackStack.current

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.batch_editor_title),
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
      LazyColumn(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(padding),
      ) {
        item {
          PreferenceSectionHeader(
            title = stringResource(R.string.batch_editor_title),
          )
        }

        item {
          PreferenceCard {
            Preference(
              title = {
                Text(stringResource(R.string.chapter_maker_title))
              },
              summary = {
                Text(
                  stringResource(R.string.chapter_maker_summary),
                  color = MaterialTheme.colorScheme.outline,
                )
              },
              onClick = {
                backStack.navigateTo(ChapterMakerScreen)
              },
            )
          }
        }

        item {
          PreferenceCard {
            Preference(
              title = {
                Text(stringResource(R.string.subtitle_editor_title))
              },
              summary = {
                Text(
                  stringResource(R.string.subtitle_editor_summary),
                  color = MaterialTheme.colorScheme.outline,
                )
              },
              onClick = {
                backStack.navigateTo(SubtitleEditorScreen)
              },
            )
          }
        }
      }
    }
  }
}
