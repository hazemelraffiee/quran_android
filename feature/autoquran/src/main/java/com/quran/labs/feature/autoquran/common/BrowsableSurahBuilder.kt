package com.quran.labs.feature.autoquran.common

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.session.MediaConstants
import com.google.common.collect.ImmutableList
import com.quran.data.core.QuranConstants.LAST_SURA
import com.quran.data.core.QuranConstants.NUMBER_OF_SURAS
import com.quran.data.core.QuranInfo
import com.quran.data.model.audio.Qari
import com.quran.data.source.PageProvider
import com.quran.labs.androidquran.common.audio.repository.CurrentQariManager
import com.quran.labs.androidquran.common.audio.util.AudioExtensionDecider
import com.quran.labs.feature.autoquran.R
import com.quran.mobile.di.qualifier.ApplicationContext
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.quran.mobile.common.ui.core.R as UiCoreR

class BrowsableSurahBuilder @Inject constructor(
  @param:ApplicationContext private val appContext: Context,
  private val pageProvider: PageProvider,
  private val audioExtensionDecider: AudioExtensionDecider,
  private val qariArtworkProvider: QariArtworkProvider,
  private val recentQariManager: RecentQariManager,
  private val quranInfo: QuranInfo,
  private val currentQariManager: CurrentQariManager,
) {

  private val recentMediaItem: MediaItem by lazy {
    MediaItem.Builder()
      .setMediaId(RECENT_ID)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(appContext.getString(R.string.autoquran_recently_played_title))
          .setIsBrowsable(true)
          .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
          .setIsPlayable(false)
          .build()
      )
      .build()
  }

  private val surahsMediaItem: MediaItem by lazy {
    MediaItem.Builder()
      .setMediaId(SURAHS_ID)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(appContext.getString(R.string.autoquran_surahs_title))
          .setIsBrowsable(true)
          .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
          .setIsPlayable(false)
          .build()
      )
      .build()
  }

  private val qariMediaItem: MediaItem by lazy {
    MediaItem.Builder()
      .setMediaId(QARI_ID)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(appContext.getString(R.string.autoquran_qaris_title))
          .setIsBrowsable(true)
          .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
          .setIsPlayable(false)
          .setExtras(Bundle().apply {
            putInt(
              MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
              MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            )
            putInt(
              MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
              MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            )
          })
          .build()
      )
      .build()
  }

  private val juzMediaItem: MediaItem by lazy {
    MediaItem.Builder()
      .setMediaId(JUZ_ID)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(appContext.getString(R.string.autoquran_juz_title))
          .setIsBrowsable(true)
          .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
          .setIsPlayable(false)
          .build()
      )
      .build()
  }

  /**
   * Get a list of child [MediaItem]s for a given media id
   */
  suspend fun children(parentId: String): ImmutableList<MediaItem> {
    return withContext(Dispatchers.IO) {
      when {
        parentId == ROOT_ID -> rootChildren()
        parentId == RECENT_ID -> recentChildren()
        parentId == SURAHS_ID -> {
          val qari = currentQariManager.currentQari()
          suraMediaItemsForQari(qari)
        }
        parentId == QARI_ID -> {
          val items = pageProvider.getQaris().map { qari -> makeQariMediaItem(qari) }
          ImmutableList.copyOf(items)
        }
        parentId == JUZ_ID -> juzChildren()
        parentId.startsWith(JUZ_ITEM_PREFIX) -> {
          val juz = parentId.substringAfter(JUZ_ITEM_PREFIX).toIntOrNull() ?: -1
          juzSuraChildren(juz)
        }
        parentId.startsWith("quran_") -> {
          val qariId = parentId.substringAfter("quran_").toIntOrNull() ?: -1
          suraMediaItemsForQariId(qariId)
        }
        else -> ImmutableList.of()
      }
    }
  }

  /**
   * Get a single [MediaItem] for a given media id.
   */
  suspend fun child(mediaId: String): MediaItem? {
    return withContext(Dispatchers.IO) {
      when {
        mediaId.startsWith("ayah_") -> {
          val parts = mediaId.split("_")
          if (parts.size != 4) return@withContext null
          val sura = parts[1].toIntOrNull() ?: return@withContext null
          val ayah = parts[2].toIntOrNull() ?: return@withContext null
          val qariId = parts[3].toIntOrNull() ?: return@withContext null
          val qari = pageProvider.getQaris().firstOrNull { it.id == qariId }
            ?: return@withContext null
          if (sura !in 1..NUMBER_OF_SURAS) return@withContext null
          if (ayah !in 1..quranInfo.getNumberOfAyahs(sura)) return@withContext null
          makeAyahMediaItem(qari, sura, ayah)
        }
        mediaId.startsWith("sura_") -> {
          val parts = mediaId.split("_")
          if (parts.size != 3) return@withContext null
          val sura = parts[1].toIntOrNull() ?: return@withContext null
          val qariId = parts[2].toIntOrNull() ?: return@withContext null
          val qari = pageProvider.getQaris().firstOrNull { it.id == qariId }
            ?: return@withContext null
          if (sura !in 1..NUMBER_OF_SURAS) return@withContext null
          makeSuraMediaItem(qari, sura)
        }
        else -> null
      }
    }
  }

  /**
   * Given a [MediaItem], return a list of [MediaItem]s to use as a playlist.
   * For gapless reciters: returns all 114 suras (current sura determines start index).
   * For gapped reciters: returns per-ayah items for the selected sura.
   */
  suspend fun expandMediaItem(mediaId: String): ImmutableList<MediaItem> {
    return withContext(Dispatchers.IO) {
      val parts = mediaId.split("_")
      if (parts.size < 3) return@withContext ImmutableList.of()
      val sura = parts[1].toIntOrNull() ?: return@withContext ImmutableList.of()
      val qariId = parts.last().toIntOrNull() ?: return@withContext ImmutableList.of()
      val qari = pageProvider.getQaris().firstOrNull { it.id == qariId }
        ?: return@withContext ImmutableList.of()

      if (qari.isGapless) {
        suraMediaItemsForQari(qari)
      } else {
        ayahMediaItemsForSura(qari, sura)
      }
    }
  }

  suspend fun search(query: String): List<MediaItem> {
    return withContext(Dispatchers.IO) {
      val defaultQari = currentQariManager.currentQari()

      val matchingQaris = pageProvider.getQaris()
        .filter { appContext.getString(it.nameResource).contains(query, true) }
        .map { makeQariMediaItem(it) }

      val suraNames = appContext.resources.getStringArray(UiCoreR.array.sura_names)
      val matchingSuras = suraNames.mapIndexedNotNull { index, name ->
        if (name.contains(query, true)) {
          makeSuraMediaItem(defaultQari, index + 1, showQariSubtitle = true)
        } else {
          null
        }
      }

      matchingQaris + matchingSuras
    }
  }

  internal fun rootChildCount(): Int {
    return if (recentQariManager.getRecentQaris().isNotEmpty()) 4 else 3
  }

  private fun rootChildren(): ImmutableList<MediaItem> {
    val items = mutableListOf<MediaItem>()
    if (recentQariManager.getRecentQaris().isNotEmpty()) {
      items.add(recentMediaItem)
    }
    items.add(surahsMediaItem)
    items.add(qariMediaItem)
    items.add(juzMediaItem)
    return ImmutableList.copyOf(items)
  }

  /**
   * Return playable [MediaItem]s for the most recently played qaris.
   * Each item represents the last sura played for that qari.
   */
  private fun recentChildren(): ImmutableList<MediaItem> {
    val recents = recentQariManager.getRecentQaris()
    if (recents.isEmpty()) return ImmutableList.of()
    val qaris = pageProvider.getQaris()
    val items = recents.mapNotNull { recent ->
      val qari = qaris.firstOrNull { it.id == recent.qariId }
      if (qari != null && recent.lastSura in 1..NUMBER_OF_SURAS) {
        makeSuraMediaItem(qari, recent.lastSura, showQariSubtitle = true)
      } else {
        null
      }
    }
    return ImmutableList.copyOf(items)
  }

  /**
   * Return 30 juz folder [MediaItem]s.
   */
  private fun juzChildren(): ImmutableList<MediaItem> {
    val items = (1..30).map { juz -> makeJuzMediaItem(juz) }
    return ImmutableList.copyOf(items)
  }

  /**
   * Return sura [MediaItem]s for a given juz, using the current default qari.
   */
  private fun juzSuraChildren(juz: Int): ImmutableList<MediaItem> {
    if (juz !in 1..30) return ImmutableList.of()
    val qari = currentQariManager.currentQari()
    val suraRange = surasForJuz(juz)
    val items = suraRange.map { sura -> makeSuraMediaItem(qari, sura) }
    return ImmutableList.copyOf(items)
  }

  /**
   * Return all 114 sura [MediaItem]s for a given [Qari].
   */
  private fun suraMediaItemsForQari(qari: Qari): ImmutableList<MediaItem> {
    return (1..NUMBER_OF_SURAS).map { sura ->
      makeSuraMediaItem(qari, sura)
    }.let { ImmutableList.copyOf(it) }
  }

  /**
   * Return all 114 sura [MediaItem]s for a given qari id.
   */
  private fun suraMediaItemsForQariId(qariId: Int): ImmutableList<MediaItem> {
    val qari = pageProvider.getQaris().firstOrNull { it.id == qariId }
      ?: return ImmutableList.of()
    return suraMediaItemsForQari(qari)
  }

  /**
   * Return per-ayah [MediaItem]s for a gapped qari's sura.
   */
  private fun ayahMediaItemsForSura(qari: Qari, sura: Int): ImmutableList<MediaItem> {
    if (sura !in 1..NUMBER_OF_SURAS) return ImmutableList.of()
    val numAyahs = quranInfo.getNumberOfAyahs(sura)
    val items = (1..numAyahs).map { ayah -> makeAyahMediaItem(qari, sura, ayah) }
    return ImmutableList.copyOf(items)
  }

  /**
   * Get the range of suras that have content within a given juz.
   */
  private fun surasForJuz(juz: Int): IntRange {
    val startPage = quranInfo.getStartingPageForJuz(juz)
    val startSura = quranInfo.getSuraOnPage(startPage)
    if (juz == 30) return startSura..LAST_SURA
    val nextJuzStartPage = quranInfo.getStartingPageForJuz(juz + 1)
    val endPage = nextJuzStartPage - 1
    val endSura = quranInfo.getSuraOnPage(endPage)
    return startSura..endSura
  }

  /**
   * Make a [MediaItem] representing a [Qari] folder
   */
  private fun makeQariMediaItem(qari: Qari): MediaItem {
    val mediaId = "quran_${qari.id}"
    val artworkUri = qariArtworkProvider.artworkUriFor(qari)
    return MediaItem.Builder()
      .setMediaId(mediaId)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(appContext.getString(qari.nameResource))
          .setIsBrowsable(true)
          .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
          .apply { setArtworkUri(artworkUri) }
          .setIsPlayable(false)
          .build()
      )
      .build()
  }

  /**
   * Make a [MediaItem] representing a juz folder
   */
  private fun makeJuzMediaItem(juz: Int): MediaItem {
    return MediaItem.Builder()
      .setMediaId("$JUZ_ITEM_PREFIX$juz")
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(appContext.getString(R.string.autoquran_juz_number_title, juz))
          .setIsBrowsable(true)
          .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
          .setIsPlayable(false)
          .build()
      )
      .build()
  }

  /**
   * Make a [MediaItem] representing a sura for a [Qari].
   * For gapless reciters, the item has a direct URI to the audio file.
   * For gapped reciters, the item has no URI -- expansion builds per-ayah items.
   */
  private fun makeSuraMediaItem(
    qari: Qari,
    sura: Int,
    showQariSubtitle: Boolean = false
  ): MediaItem {
    val suraName = getSuraName(appContext, sura, wantPrefix = true, wantTranslation = false)
    val artworkUri = qariArtworkProvider.suraArtworkUriFor(qari, sura)
    val qariName = appContext.getString(qari.nameResource)

    val builder = MediaItem.Builder()
      .setMediaId("sura_${sura}_${qari.id}")
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setIsBrowsable(false)
          .setIsPlayable(true)
          .setTitle(suraName)
          .setDisplayTitle(suraName)
          .setTrackNumber(sura)
          .setTotalTrackCount(NUMBER_OF_SURAS)
          .setArtist(qariName)
          .apply {
            if (showQariSubtitle) setSubtitle(qariName)
            setArtworkUri(artworkUri)
          }
          .build()
      )

    if (qari.isGapless) {
      val extension = audioExtensionDecider.audioExtensionForQari(qari)
      val (baseUrl, mimeType) = if (extension == "opus" && qari.opusUrl != null) {
        qari.opusUrl to MimeTypes.AUDIO_OPUS
      } else {
        qari.url to MimeTypes.AUDIO_MPEG
      }
      builder.setMimeType(mimeType)
      builder.setUri(baseUrl + makeThreeDigit(sura) + ".$extension")
    }

    return builder.build()
  }

  /**
   * Make a [MediaItem] representing a single ayah for a gapped [Qari].
   */
  private fun makeAyahMediaItem(qari: Qari, sura: Int, ayah: Int): MediaItem {
    val suraName = getSuraName(appContext, sura, wantPrefix = true, wantTranslation = false)
    val qariName = appContext.getString(qari.nameResource)
    val extension = audioExtensionDecider.audioExtensionForQari(qari)
    val (baseUrl, mimeType) = if (extension == "opus" && qari.opusUrl != null) {
      qari.opusUrl to MimeTypes.AUDIO_OPUS
    } else {
      qari.url to MimeTypes.AUDIO_MPEG
    }
    val uri = baseUrl + makeThreeDigit(sura) + makeThreeDigit(ayah) + ".$extension"
    val artworkUri = qariArtworkProvider.suraArtworkUriFor(qari, sura)

    return MediaItem.Builder()
      .setMediaId("ayah_${sura}_${ayah}_${qari.id}")
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setIsBrowsable(false)
          .setIsPlayable(true)
          .setTitle("$suraName - $ayah")
          .setArtist(qariName)
          .setTrackNumber(ayah)
          .setTotalTrackCount(quranInfo.getNumberOfAyahs(sura))
          .setArtworkUri(artworkUri)
          .build()
      )
      .setMimeType(mimeType)
      .setUri(uri)
      .build()
  }

  companion object {
    const val ROOT_ID = "__ROOT__"
    const val QARI_ID = "__QARI__"
    const val RECENT_ID = "__RECENT__"
    const val SURAHS_ID = "__SURAHS__"
    const val JUZ_ID = "__JUZ__"
    const val JUZ_ITEM_PREFIX = "juz_"
  }
}
