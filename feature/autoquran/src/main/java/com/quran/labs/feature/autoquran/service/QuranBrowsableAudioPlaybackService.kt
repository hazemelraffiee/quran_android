package com.quran.labs.feature.autoquran.service

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.quran.labs.androidquran.common.audio.repository.CurrentQariManager
import com.quran.labs.feature.autoquran.common.BrowsableSurahBuilder
import com.quran.labs.feature.autoquran.common.RecentQariManager
import com.quran.labs.feature.autoquran.di.QuranAutoInjector
import com.quran.mobile.di.QuranApplicationComponentProvider
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

@OptIn(UnstableApi::class)
class QuranBrowsableAudioPlaybackService : MediaLibraryService() {
  @Inject
  lateinit var surahBuilder: BrowsableSurahBuilder

  @Inject
  lateinit var recentQariManager: RecentQariManager

  @Inject
  lateinit var currentQariManager: CurrentQariManager

  private var mediaSession: MediaLibrarySession? = null
  @Volatile private var cachedSearch: Pair<String, List<MediaItem>>? = null

  private val playerListener = PlayerEventListener()
  private val quranAudioAttributes = AudioAttributes.Builder()
    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
    .setUsage(C.USAGE_MEDIA)
    .build()

  private var exoPlayer: Player? = null

  private val rootMediaItem: MediaItem by lazy {
    MediaItem.Builder()
      .setMediaId(BrowsableSurahBuilder.ROOT_ID)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setIsBrowsable(true)
          .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
          .setIsPlayable(false)
          .build()
      )
      .build()
  }

  private val recentRootMediaItem: MediaItem by lazy {
    MediaItem.Builder()
      .setMediaId(BrowsableSurahBuilder.RECENT_ID)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setIsBrowsable(true)
          .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
          .setIsPlayable(false)
          .build()
      )
      .build()
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onCreate() {
    super.onCreate()
    val injector = (application as? QuranApplicationComponentProvider)
      ?.provideQuranApplicationComponent() as? QuranAutoInjector
    if (injector == null) {
      Timber.e(
        "Unable to inject QuranBrowsableAudioPlaybackService" +
          " (component missing or wrong type)"
      )
      stopSelf()
      return
    }
    injector.inject(this)

    val player = ExoPlayer.Builder(this).build().apply {
      setAudioAttributes(quranAudioAttributes, true)
      setHandleAudioBecomingNoisy(true)
      addListener(playerListener)
    }
    exoPlayer = player

    mediaSession = MediaLibrarySession.Builder(
      this, player, QuranServiceCallback()
    ).build()
  }

  override fun onDestroy() {
    scope.cancel()
    exoPlayer?.apply {
      removeListener(playerListener)
      release()
    }
    exoPlayer = null
    mediaSession?.release()
    mediaSession = null
    super.onDestroy()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
    mediaSession

  private inner class PlayerEventListener : Player.Listener {
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
      val mediaId = mediaItem?.mediaId ?: return
      val parts = mediaId.split("_")

      val sura: Int
      val qariId: Int
      if (mediaId.startsWith("sura_") && parts.size == 3) {
        sura = parts[1].toIntOrNull() ?: return
        qariId = parts[2].toIntOrNull() ?: return
      } else if (mediaId.startsWith("ayah_") && parts.size == 4) {
        sura = parts[1].toIntOrNull() ?: return
        qariId = parts[3].toIntOrNull() ?: return
      } else {
        return
      }

      if (::recentQariManager.isInitialized) {
        recentQariManager.recordQari(qariId, sura)
        val recentCount = recentQariManager.getRecentQaris().size
        mediaSession?.notifyChildrenChanged(
          BrowsableSurahBuilder.RECENT_ID, recentCount, null
        )
      }
      if (::surahBuilder.isInitialized) {
        mediaSession?.notifyChildrenChanged(
          BrowsableSurahBuilder.ROOT_ID, surahBuilder.rootChildCount(), null
        )
      }
      if (::currentQariManager.isInitialized) {
        currentQariManager.setCurrentQari(qariId)
      }
    }
  }

  private inner class QuranServiceCallback : MediaLibrarySession.Callback {

    override fun onSubscribe(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      parentId: String,
      params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
      return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetLibraryRoot(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
      if (params?.isRecent == true) {
        val recentParams = MediaLibraryService.LibraryParams.Builder()
          .setRecent(true)
          .build()
        return Futures.immediateFuture(
          LibraryResult.ofItem(recentRootMediaItem, recentParams)
        )
      }
      val rootExtras = Bundle().apply {
        putInt(
          MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
          MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )
        putInt(
          MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
          MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
      }
      val libraryParams = MediaLibraryService.LibraryParams.Builder().setExtras(rootExtras).build()
      return Futures.immediateFuture(LibraryResult.ofItem(rootMediaItem, libraryParams))
    }

    override fun onGetItem(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
      val settable = SettableFuture.create<LibraryResult<MediaItem>>()
      scope.launch {
        val result = runCatching {
          val item = surahBuilder.child(mediaId)
          if (item == null) {
            LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
          } else {
            LibraryResult.ofItem(item, MediaLibraryService.LibraryParams.Builder().build())
          }
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
      params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
      Timber.d("onGetChildren(parentId=$parentId page=$page pageSize=$pageSize)")
      val settable = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
      scope.launch {
        val result = runCatching {
          val children = surahBuilder.children(parentId)
          LibraryResult.ofItemList(children, MediaLibraryService.LibraryParams.Builder().build())
        }.getOrElse { t ->
          Timber.e(t, "onGetChildren failed for parentId=$parentId")
          // Important: always respond, otherwise Android Auto can show an infinite spinner.
          LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
        }
        settable.set(result)
      }
      return settable
    }

    override fun onAddMediaItems(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo,
      mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
      val settable = SettableFuture.create<List<MediaItem>>()
      scope.launch {
        val items = runCatching {
          var resolved = mediaItems.mapNotNull { item ->
            val child = surahBuilder.child(item.mediaId)
            if (child != null && child.localConfiguration?.uri == null) {
              // Gapped reciter surah item has no URI; expand to per-ayah items
              return@mapNotNull null
            }
            child
          }

          // Handle gapped reciter items: expand to per-ayah playlist
          if (resolved.isEmpty() && mediaItems.isNotEmpty()) {
            val firstItem = mediaItems.first()
            // Check for voice search query
            val searchQuery = firstItem.requestMetadata.searchQuery
            resolved = if (searchQuery != null) {
              surahBuilder.search(searchQuery)
            } else if (firstItem.mediaId.startsWith("sura_")) {
              surahBuilder.expandMediaItem(firstItem.mediaId).toList()
            } else {
              emptyList()
            }
          }

          resolved
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
      startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
      return if (mediaItems.size == 1) {
        val settable = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
          val result = runCatching {
            val firstItem = mediaItems.first()
            val items = surahBuilder.expandMediaItem(firstItem.mediaId)
            val index = items.indexOfFirst { it.mediaId == firstItem.mediaId }
            val startPosition = if (index != -1) index else 0
            MediaSession.MediaItemsWithStartPosition(items, startPosition, 0)
          }.getOrElse { t ->
            Timber.e(t, "onSetMediaItems failed")
            MediaSession.MediaItemsWithStartPosition(ImmutableList.of(), 0, 0)
          }
          settable.set(result)
        }
        settable
      } else {
        super.onSetMediaItems(
          mediaSession,
          controller,
          mediaItems,
          startIndex,
          startPositionMs
        )
      }
    }

    override fun onSearch(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      query: String,
      params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
      val settable = SettableFuture.create<LibraryResult<Void>>()
      scope.launch {
        val result = runCatching {
          val results = surahBuilder.search(query)
          cachedSearch = query to results
          session.notifySearchResultChanged(
            browser, query, results.size, params
          )
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
      params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
      val settable =
        SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
      scope.launch {
        val result = runCatching {
          val cached = cachedSearch
          val items = if (cached != null && cached.first == query) {
            cached.second
          } else {
            surahBuilder.search(query)
          }
          LibraryResult.ofItemList(
            items,
            MediaLibraryService.LibraryParams.Builder().build()
          )
        }.getOrElse { t ->
          Timber.e(t, "onGetSearchResult failed for query=$query")
          LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
        }
        settable.set(result)
      }
      return settable
    }
  }

}
