package com.quran.labs.androidquran.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.quran.data.core.QuranInfo
import com.quran.data.model.SuraAyah
import com.quran.data.source.PageProvider
import com.quran.labs.androidquran.common.audio.model.QariItem
import com.quran.labs.androidquran.common.audio.model.playback.AudioRequest
import com.quran.labs.androidquran.common.audio.repository.CurrentQariManager
import com.quran.labs.androidquran.common.audio.util.AudioPathInfoBuilder
import com.quran.labs.androidquran.util.AudioUtilsInterface
import com.quran.labs.feature.autoquran.common.BrowsableSurahBuilder
import com.quran.labs.feature.autoquran.common.RecentQariManager
import com.quran.mobile.di.qualifier.ApplicationContext
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.quran.data.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Unified MediaLibrarySession callback. Serves Android Auto's browse + play surface and
 * routes play requests through [StartPlaybackCallback.startPlayback] so the phone's
 * AudioQueue/word-highlighting pipeline stays in sync with Auto-initiated playback.
 *
 * The service wires itself as the [StartPlaybackCallback] after DI completes via
 * [attach] — breaks what would otherwise be a constructor-time cycle between the service
 * and its own session callback.
 */
interface StartPlaybackCallback {
  fun startPlayback(request: AudioRequest)
}

@OptIn(UnstableApi::class)
@SingleIn(AppScope::class)
class QuranServiceCallback @Inject constructor(
  @param:ApplicationContext private val appContext: Context,
  private val pageProvider: PageProvider,
  private val quranInfo: QuranInfo,
  private val surahBuilder: BrowsableSurahBuilder,
  private val audioPathInfoBuilder: AudioPathInfoBuilder,
  private val audioUtils: AudioUtilsInterface,
  private val currentQariManager: CurrentQariManager,
  private val recentQariManager: RecentQariManager,
) : MediaLibrarySession.Callback {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private var startPlaybackCallback: StartPlaybackCallback? = null

  private val rootMediaItem: MediaItem by lazy {
    MediaItem.Builder()
      .setMediaId(BrowsableSurahBuilder.ROOT_ID)
      .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
          .setIsBrowsable(true)
          .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_MIXED)
          .setIsPlayable(false)
          .build()
      )
      .build()
  }

  private val recentRootMediaItem: MediaItem by lazy {
    MediaItem.Builder()
      .setMediaId(BrowsableSurahBuilder.RECENT_ID)
      .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
          .setIsBrowsable(true)
          .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_MIXED)
          .setIsPlayable(false)
          .build()
      )
      .build()
  }

  fun attach(callback: StartPlaybackCallback) {
    startPlaybackCallback = callback
  }

  fun detach() {
    startPlaybackCallback = null
    scope.cancel()
  }

  override fun onGetLibraryRoot(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<MediaItem>> {
    if (params?.isRecent == true) {
      val recentParams = LibraryParams.Builder().setRecent(true).build()
      return Futures.immediateFuture(LibraryResult.ofItem(recentRootMediaItem, recentParams))
    }
    val rootExtras = android.os.Bundle().apply {
      putInt(
        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
      )
      putInt(
        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
      )
    }
    val libraryParams = LibraryParams.Builder().setExtras(rootExtras).build()
    return Futures.immediateFuture(LibraryResult.ofItem(rootMediaItem, libraryParams))
  }

  override fun onGetItem(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    mediaId: String,
  ): ListenableFuture<LibraryResult<MediaItem>> {
    val settable = SettableFuture.create<LibraryResult<MediaItem>>()
    scope.launch {
      val result = runCatching {
        val item =
          if (mediaId == BrowsableSurahBuilder.ROOT_ID) rootMediaItem
          else surahBuilder.child(mediaId)
        if (item == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        else LibraryResult.ofItem(item, LibraryParams.Builder().build())
      }.getOrElse { t ->
        Timber.e(t, "onGetItem failed for mediaId=$mediaId")
        LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
      }
      settable.set(result)
    }
    return settable
  }

  override fun onGetChildren(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    parentId: String,
    page: Int,
    pageSize: Int,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    val settable = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
    scope.launch {
      val result = runCatching {
        val children = surahBuilder.children(parentId)
        LibraryResult.ofItemList(children, LibraryParams.Builder().build())
      }.getOrElse { t ->
        Timber.e(t, "onGetChildren failed for parentId=$parentId")
        // Important: always respond, otherwise Auto can show an infinite spinner.
        LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
      }
      settable.set(result)
    }
    return settable
  }

  override fun onAddMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: List<MediaItem>,
  ): ListenableFuture<List<MediaItem>> {
    val settable = SettableFuture.create<List<MediaItem>>()
    scope.launch {
      val items = runCatching {
        mediaItems.mapNotNull { surahBuilder.child(it.mediaId) }
      }.getOrElse { t ->
        Timber.e(t, "onAddMediaItems failed")
        emptyList()
      }
      settable.set(items)
    }
    return settable
  }

  override fun onSetMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    if (mediaItems.size != 1) {
      return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
    }
    val settable = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
    scope.launch {
      val result = runCatching {
        val firstItem = mediaItems.first()
        val mediaId = firstItem.mediaId
        val items = surahBuilder.expandMediaItem(mediaId)
        val index = items.indexOfFirst { it.mediaId == mediaId }.coerceAtLeast(0)

        // Parse (sura, qariId) from `sura_<sura>_<qariId>`; route through startPlayback
        // so the phone AudioQueue + word-highlight pipeline stays aligned with Auto.
        val (sura, qariId) = parseSuraMediaId(mediaId)
        if (sura != null && qariId != null) {
          val audioRequest = buildAudioRequest(sura, qariId)
          if (audioRequest != null) {
            scope.launch { currentQariManager.setCurrentQari(qariId) }
            startPlaybackCallback?.startPlayback(audioRequest)
              ?: Timber.w("startPlaybackCallback is null; Auto play request dropped")
          }
        } else {
          Timber.w("onSetMediaItems received unparseable mediaId=$mediaId")
        }

        MediaSession.MediaItemsWithStartPosition(items, index, 0L)
      }.getOrElse { t ->
        Timber.e(t, "onSetMediaItems failed")
        MediaSession.MediaItemsWithStartPosition(ImmutableList.of(), 0, 0)
      }
      settable.set(result)
    }
    return settable
  }

  override fun onSearch(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<Void>> {
    val settable = SettableFuture.create<LibraryResult<Void>>()
    scope.launch {
      val result = runCatching {
        session.notifySearchResultChanged(browser, query, surahBuilder.search(query).size, params)
        LibraryResult.ofVoid(params)
      }.getOrElse { t ->
        Timber.e(t, "onSearch failed for query=$query")
        LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
      }
      settable.set(result)
    }
    return settable
  }

  override fun onGetSearchResult(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    page: Int,
    pageSize: Int,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    val settable = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
    scope.launch {
      val result = runCatching {
        val items = ImmutableList.copyOf(surahBuilder.search(query))
        LibraryResult.ofItemList(items, LibraryParams.Builder().build())
      }.getOrElse { t ->
        Timber.e(t, "onGetSearchResult failed for query=$query")
        LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
      }
      settable.set(result)
    }
    return settable
  }

  /**
   * Parse mediaIds of the form `sura_<sura>_<qariId>`. Returns `(null, null)` for any
   * other pattern (including browsable root/qari-folder ids).
   */
  private fun parseSuraMediaId(mediaId: String): Pair<Int?, Int?> {
    if (!mediaId.startsWith("sura_")) return null to null
    val rest = mediaId.removePrefix("sura_")
    val sura = rest.substringBefore("_").toIntOrNull()
    val qari = rest.substringAfter("_", missingDelimiterValue = "").toIntOrNull()
    return sura to qari
  }

  /**
   * Module-internal entry point used by the service's [Player.Listener.onMediaItemTransition]
   * bridge to rebuild the per-sura [AudioRequest] when ExoPlayer auto-advances within
   * Auto's 114-item playlist.
   */
  internal fun buildAudioRequestInternal(sura: Int, qariId: Int): AudioRequest? =
    buildAudioRequest(sura, qariId)

  private fun buildAudioRequest(sura: Int, qariId: Int): AudioRequest? {
    val qari = pageProvider.getQaris().firstOrNull { it.id == qariId } ?: run {
      Timber.w("Auto request for unknown qariId=$qariId")
      return null
    }
    val qariItem = QariItem.fromQari(appContext, qari)
    val localPath = audioUtils.getLocalQariUrl(qariItem)
    val databasePath = audioUtils.getQariDatabasePathIfGapless(qariItem)
    val audioPathInfo = audioPathInfoBuilder.build(qariItem, localPath, databasePath) ?: run {
      Timber.w("Auto request: AudioPathInfo null for qariId=$qariId (no local dir)")
      return null
    }
    val start = SuraAyah(sura, 1)
    val end = SuraAyah(sura, quranInfo.getNumberOfAyahs(sura))
    val shouldStream = !audioUtils.haveAllFiles(
      audioPathInfo.urlFormat,
      audioPathInfo.localDirectory,
      start,
      end,
      qari.isGapless,
      audioPathInfo.allowedExtensions,
    )
    return AudioRequest(
      start = start,
      end = end,
      qari = qariItem,
      repeatInfo = 0,
      rangeRepeatInfo = 0,
      playbackSpeed = 1f,
      audioPathInfo = audioPathInfo,
      shouldStream = shouldStream,
      wordHighlighting = false,
      enforceBounds = true,
    )
  }
}
