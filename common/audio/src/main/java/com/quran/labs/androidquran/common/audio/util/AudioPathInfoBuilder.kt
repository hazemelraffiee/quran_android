package com.quran.labs.androidquran.common.audio.util

import com.quran.data.di.AppScope
import com.quran.labs.androidquran.common.audio.model.QariItem
import com.quran.labs.androidquran.common.audio.model.playback.AudioPathInfo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File

/**
 * Builds [AudioPathInfo] for a [QariItem] from its resolved local directory and optional
 * gapless timing database path. Shared by the reading-screen [AudioPresenter] and, from
 * Cycle 3 onwards, by the Android Auto playback callback so both paths construct identical
 * path info for the same qari.
 *
 * The builder deliberately does not own the resolution of local paths — that still lives
 * in `AudioUtilsInterface` (app-module, to avoid moving its Android-specific storage logic
 * into `:common:audio`). Callers pass in the resolved values.
 */
@SingleIn(AppScope::class)
class AudioPathInfoBuilder @Inject constructor(
  private val audioExtensionDecider: AudioExtensionDecider,
) {
  /**
   * @param qari the qari being prepared.
   * @param localPath resolved base directory for this qari's local audio, or null if the
   *   qari is not downloaded / storage is unavailable.
   * @param databasePath gapless timing database path if the qari is gapless, else null.
   * @return an [AudioPathInfo] whose `urlFormat` expects `%d/%d` (non-gapless) or `%03d`
   *   (gapless) substitution, or null if [localPath] is null.
   */
  fun build(qari: QariItem, localPath: String?, databasePath: String?): AudioPathInfo? {
    if (localPath == null) return null
    val extension = audioExtensionDecider.audioExtensionForQari(qari)
    val urlFormat = if (databasePath.isNullOrEmpty()) {
      localPath + File.separator + "%d" + File.separator + "%d" + ".$extension"
    } else {
      localPath + File.separator + "%03d" + ".$extension"
    }
    return AudioPathInfo(
      urlFormat,
      localPath,
      databasePath,
      audioExtensionDecider.allowedAudioExtensions(qari),
    )
  }
}
