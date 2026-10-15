package app.gyrolet.mpvrx.utils.batcheditor

import android.content.Context
import android.os.ParcelFileDescriptor
import mkvbridge.Mkvbridge
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

object BatchEditorBridge {
  private val json =
    Json {
      ignoreUnknownKeys = true
    }

  @Serializable
  data class ChapterRequest(
    val title: String,
    val mode: String,
    val minutes: Int = 0,
    val seconds: Int = 0,
    @SerialName("start_minutes")
    val startMinutes: Int = 0,
    @SerialName("start_seconds")
    val startSeconds: Int = 0,
    @SerialName("end_minutes")
    val endMinutes: Int = 0,
    @SerialName("end_seconds")
    val endSeconds: Int = 0,
    @SerialName("end_at_duration")
    val endAtDuration: Boolean = false,
  )

  @Serializable
  data class SubtitleEditRequest(
    @SerialName("subtitle_index")
    val subtitleIndex: Int,
    val name: String = "",
    @SerialName("change_name")
    val changeName: Boolean = false,
    @SerialName("default_mode")
    val defaultMode: String = "",
    @SerialName("new_position")
    val newPosition: Int = 0,
  )

  @Serializable
  data class SubtitleTrack(
    val index: Int,
    val id: Long,
    val name: String,
    val language: String,
    val codec: String,
    @SerialName("is_default")
    val isDefault: Boolean,
    @SerialName("is_forced")
    val isForced: Boolean,
  )

  fun editChapters(
    context: Context,
    pfd: ParcelFileDescriptor,
    request: ChapterRequest,
  ) {
    Mkvbridge.editChapters(
      pfd.fd.toLong(),
      context.cacheDir.absolutePath,
      json.encodeToString(request),
    )
  }

  fun clearChapters(
    context: Context,
    pfd: ParcelFileDescriptor,
  ) {
    Mkvbridge.clearChapters(
      pfd.fd.toLong(),
      context.cacheDir.absolutePath,
    )
  }

  fun inspectSubtitleTracks(
    pfd: ParcelFileDescriptor,
  ): List<SubtitleTrack> {
    return json.decodeFromString(
      Mkvbridge.inspectSubtitleTracks(
        pfd.fd.toLong(),
      ),
    )
  }

  fun editSubtitleTracks(
    context: Context,
    pfd: ParcelFileDescriptor,
    request: SubtitleEditRequest,
  ) {
    Mkvbridge.editSubtitleTracks(
      pfd.fd.toLong(),
      context.cacheDir.absolutePath,
      json.encodeToString(request),
    )
  }
}
