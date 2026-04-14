package com.quran.labs.androidquran.common.audio.util

import com.google.common.truth.Truth.assertThat
import com.quran.data.model.audio.Qari
import com.quran.labs.androidquran.common.audio.model.QariItem
import org.junit.Test

class AudioPathInfoBuilderTest {

  private val builder = AudioPathInfoBuilder(FakeExtensionDecider())

  @Test
  fun `returns null when localPath is null`() {
    val result = builder.build(qari(id = 1, isGapless = true), localPath = null, databasePath = "/any/db")
    assertThat(result).isNull()
  }

  @Test
  fun `gapless qari uses three-digit sura format`() {
    val result = builder.build(
      qari(id = 1, isGapless = true),
      localPath = "/data/audio/gapless",
      databasePath = "/data/audio/gapless/timing.db",
    )
    assertThat(result).isNotNull()
    assertThat(result!!.urlFormat).isEqualTo("/data/audio/gapless/%03d.mp3")
    assertThat(result.localDirectory).isEqualTo("/data/audio/gapless")
    assertThat(result.gaplessDatabase).isEqualTo("/data/audio/gapless/timing.db")
  }

  @Test
  fun `non-gapless qari uses sura-slash-ayah format`() {
    val result = builder.build(
      qari(id = 2, isGapless = false),
      localPath = "/data/audio/gapped",
      databasePath = null,
    )
    assertThat(result).isNotNull()
    assertThat(result!!.urlFormat).isEqualTo("/data/audio/gapped/%d/%d.mp3")
    assertThat(result.gaplessDatabase).isNull()
  }

  @Test
  fun `empty databasePath is treated as non-gapless`() {
    val result = builder.build(
      qari(id = 3, isGapless = false),
      localPath = "/data/audio/x",
      databasePath = "",
    )
    assertThat(result!!.urlFormat).isEqualTo("/data/audio/x/%d/%d.mp3")
  }

  @Test
  fun `allowedExtensions is passed through`() {
    val decider = FakeExtensionDecider(allowed = listOf("opus", "mp3"))
    val result = AudioPathInfoBuilder(decider).build(
      qari(id = 1, isGapless = true),
      localPath = "/x",
      databasePath = "/x/db",
    )
    assertThat(result!!.allowedExtensions).containsExactly("opus", "mp3").inOrder()
  }

  private fun qari(id: Int, isGapless: Boolean): QariItem =
    QariItem(
      id = id,
      name = "test",
      url = "",
      path = "",
      hasGaplessAlternative = false,
      db = if (isGapless) "timing.db" else null,
    )

  private class FakeExtensionDecider(
    private val extension: String = "mp3",
    private val allowed: List<String> = listOf("mp3"),
  ) : AudioExtensionDecider {
    override fun audioExtensionForQari(qari: Qari): String = extension
    override fun audioExtensionForQari(qariItem: QariItem): String = extension
    override fun allowedAudioExtensions(qari: Qari): List<String> = allowed
    override fun allowedAudioExtensions(qari: QariItem): List<String> = allowed
  }
}
