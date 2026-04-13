package com.quran.labs.androidquran.service

import androidx.media3.session.MediaLibraryService

/**
 * Empty callback implementation, to be extended to support media items and allow controller
 * requests from other apps (like Android Auto or Google Assistant). The full browse tree
 * and playback dispatch live in a later cycle; this stub exists so the MediaLibrarySession
 * can be instantiated today with a valid callback.
 */
class QuranServiceCallback : MediaLibraryService.MediaLibrarySession.Callback
