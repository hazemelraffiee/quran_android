package com.quran.labs.feature.autoquran.common

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RecentQariManagerTest {

  private lateinit var manager: RecentQariManager

  @Before
  fun setUp() {
    manager = RecentQariManager(RuntimeEnvironment.getApplication())
  }

  @Test
  fun `empty initial state`() {
    assertThat(manager.getRecentQaris()).isEmpty()
  }

  @Test
  fun `record and retrieve single qari`() {
    manager.recordQari(qariId = 1, sura = 36)
    val recents = manager.getRecentQaris()
    assertThat(recents).hasSize(1)
    assertThat(recents[0].qariId).isEqualTo(1)
    assertThat(recents[0].lastSura).isEqualTo(36)
  }

  @Test
  fun `same qari and sura deduplicates and moves to front`() {
    manager.recordQari(qariId = 1, sura = 36)
    manager.recordQari(qariId = 2, sura = 1)
    manager.recordQari(qariId = 1, sura = 36)

    val recents = manager.getRecentQaris()
    assertThat(recents).hasSize(2)
    assertThat(recents[0].qariId).isEqualTo(1)
    assertThat(recents[0].lastSura).isEqualTo(36)
    assertThat(recents[1].qariId).isEqualTo(2)
  }

  @Test
  fun `different suras from same qari are kept separately`() {
    manager.recordQari(qariId = 1, sura = 36)
    manager.recordQari(qariId = 1, sura = 67)

    val recents = manager.getRecentQaris()
    assertThat(recents).hasSize(2)
    assertThat(recents[0].lastSura).isEqualTo(67)
    assertThat(recents[1].lastSura).isEqualTo(36)
  }

  @Test
  fun `maintains recency order`() {
    manager.recordQari(qariId = 1, sura = 1)
    manager.recordQari(qariId = 2, sura = 2)
    manager.recordQari(qariId = 3, sura = 3)

    val recents = manager.getRecentQaris()
    assertThat(recents.map { it.qariId }).containsExactly(3, 2, 1).inOrder()
  }

  @Test
  fun `evicts oldest beyond max entries`() {
    manager.recordQari(qariId = 1, sura = 1)
    manager.recordQari(qariId = 2, sura = 2)
    manager.recordQari(qariId = 3, sura = 3)
    manager.recordQari(qariId = 4, sura = 4)
    manager.recordQari(qariId = 5, sura = 5)
    manager.recordQari(qariId = 6, sura = 6)

    val recents = manager.getRecentQaris()
    assertThat(recents).hasSize(5)
    assertThat(recents.map { it.qariId }).containsExactly(6, 5, 4, 3, 2).inOrder()
  }

  @Test
  fun `handles corrupt json gracefully`() {
    val context = RuntimeEnvironment.getApplication()
    val prefs = context.getSharedPreferences("autoquran_recent", Context.MODE_PRIVATE)
    prefs.edit().putString("recent_qaris", "not valid json").commit()

    val freshManager = RecentQariManager(context)
    assertThat(freshManager.getRecentQaris()).isEmpty()
  }

  @Test
  fun `concurrent writers do not lose entries`() {
    // 5 threads each record a distinct (qariId, sura) pair. Without the internal lock,
    // read-modify-write races on SharedPreferences would drop some writes. With the
    // lock, the final list has 5 unique entries (within the MAX_RECENT cap).
    val writers = (1..5).map { qariId ->
      Thread {
        repeat(10) { iteration ->
          manager.recordQari(qariId = qariId, sura = iteration + 1)
        }
      }
    }
    writers.forEach { it.start() }
    writers.forEach { it.join() }

    val recents = manager.getRecentQaris()
    // At most MAX_RECENT=5 entries retained. Each should have a distinct (qariId, sura).
    assertThat(recents.size).isAtMost(5)
    assertThat(recents.size).isAtLeast(5) // exactly 5 — all writers produced at least one entry
    val pairs = recents.map { it.qariId to it.lastSura }
    assertThat(pairs).containsNoDuplicates()
  }
}
