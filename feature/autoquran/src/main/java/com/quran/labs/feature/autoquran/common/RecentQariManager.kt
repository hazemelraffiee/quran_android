package com.quran.labs.feature.autoquran.common

import android.content.Context
import android.content.SharedPreferences
import com.quran.mobile.di.qualifier.ApplicationContext
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dev.zacsweers.metro.Inject
import timber.log.Timber
import androidx.core.content.edit

class RecentQariManager @Inject constructor(
  @ApplicationContext appContext: Context,
) {

  private val prefs: SharedPreferences =
    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val adapter = Moshi.Builder().build().adapter<List<RecentQari>>(
    Types.newParameterizedType(List::class.java, RecentQari::class.java)
  )

  // Guards read-modify-write on the prefs-backed list. Both the Media3 player thread
  // (via RecentPlaybackRecorder) and the main thread (via Auto/phone transitions) can
  // invoke these methods; without the lock, concurrent writers can clobber each other.
  private val lock = Any()

  fun getRecentQaris(): List<RecentQari> = synchronized(lock) {
    val json = prefs.getString(KEY_RECENT_QARIS, null) ?: return emptyList()
    return try {
      adapter.fromJson(json) ?: emptyList()
    } catch (e: Exception) {
      Timber.e(e, "Failed to parse recent qaris")
      emptyList()
    }
  }

  fun recordQari(qariId: Int, sura: Int) = synchronized(lock) {
    val current = getRecentQarisUnlocked().toMutableList()
    current.removeAll { it.qariId == qariId && it.lastSura == sura }
    current.add(0, RecentQari(qariId, sura, System.currentTimeMillis()))
    val trimmed = current.take(MAX_RECENT)
    prefs.edit { putString(KEY_RECENT_QARIS, adapter.toJson(trimmed)) }
  }

  private fun getRecentQarisUnlocked(): List<RecentQari> {
    val json = prefs.getString(KEY_RECENT_QARIS, null) ?: return emptyList()
    return try {
      adapter.fromJson(json) ?: emptyList()
    } catch (e: Exception) {
      Timber.e(e, "Failed to parse recent qaris")
      emptyList()
    }
  }

  companion object {
    private const val PREFS_NAME = "autoquran_recent"
    private const val KEY_RECENT_QARIS = "recent_qaris"
    private const val MAX_RECENT = 5
  }
}
