package com.quran.labs.feature.autoquran.common

import com.google.common.truth.Truth.assertThat
import com.quran.data.core.QuranInfo
import com.quran.data.model.audio.Qari
import com.quran.data.source.DisplaySize
import com.quran.data.source.PageProvider
import com.quran.data.source.PageSizeCalculator
import com.quran.data.source.QuranDataSource
import com.quran.labs.androidquran.common.audio.model.QariItem
import com.quran.labs.androidquran.common.audio.repository.CurrentQariManager
import com.quran.labs.androidquran.common.audio.util.AudioExtensionDecider
import com.quran.labs.androidquran.common.audio.util.QariUtil
import com.quran.labs.androidquran.pages.data.madani.MadaniDataSource
import com.quran.labs.androidquran.common.audio.R as audioR
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BrowsableSurahBuilderTest {

  private lateinit var recentQariManager: RecentQariManager
  private lateinit var currentQariManager: CurrentQariManager
  private lateinit var builder: BrowsableSurahBuilder

  private val testQaris = listOf(
    Qari(
      0,
      audioR.string.qari_minshawi_murattal_gapless,
      url = "https://download.quranicaudio.com/quran/muhammad_siddeeq_al-minshaawee/",
      path = "minshawi_murattal",
      hasGaplessAlternative = false,
      db = "minshawi_murattal"
    ),
    Qari(
      1,
      audioR.string.qari_husary_gapless,
      url = "https://download.quranicaudio.com/quran/mahmood_khaleel_al-husaree/",
      path = "husary",
      hasGaplessAlternative = false,
      db = "husary"
    ),
    Qari(
      2,
      audioR.string.qari_basfar,
      url = "https://mirrors.quranicaudio.com/everyayah/Abdullah_Basfar_192kbps/",
      path = "2",
      hasGaplessAlternative = false,
      db = null
    )
  )

  private val fakePageProvider = object : PageProvider {
    override fun getDataSource(): QuranDataSource = MadaniDataSource()
    override fun getPageSizeCalculator(displaySize: DisplaySize): PageSizeCalculator =
      throw NotImplementedError()
    override fun getImageVersion(): Int = throw NotImplementedError()
    override fun getImagesBaseUrl(): String = throw NotImplementedError()
    override fun getImagesZipBaseUrl(): String = throw NotImplementedError()
    override fun getPatchBaseUrl(): String = throw NotImplementedError()
    override fun getAyahInfoBaseUrl(): String = throw NotImplementedError()
    override fun getDatabasesBaseUrl(): String = throw NotImplementedError()
    override fun getAudioDatabasesBaseUrl(): String = throw NotImplementedError()
    override fun getAudioDirectoryName(): String = throw NotImplementedError()
    override fun getDatabaseDirectoryName(): String = throw NotImplementedError()
    override fun getAyahInfoDirectoryName(): String = throw NotImplementedError()
    override fun getImagesDirectoryName(): String = throw NotImplementedError()
    override fun getPreviewTitle(): Int = throw NotImplementedError()
    override fun getPreviewDescription(): Int = throw NotImplementedError()
    override fun getQaris(): List<Qari> = testQaris
    override fun getDefaultQariId(): Int = 0
  }

  private val fakeAudioExtensionDecider = object : AudioExtensionDecider {
    override fun audioExtensionForQari(qari: Qari): String = "mp3"
    override fun audioExtensionForQari(qariItem: QariItem): String = "mp3"
    override fun allowedAudioExtensions(qari: Qari): List<String> = listOf("mp3")
    override fun allowedAudioExtensions(qariItem: QariItem): List<String> = listOf("mp3")
  }

  @Before
  fun setUp() {
    val context = RuntimeEnvironment.getApplication()
    recentQariManager = RecentQariManager(context)
    val qariUtil = QariUtil(fakePageProvider)
    currentQariManager = CurrentQariManager(context, qariUtil)
    val quranInfo = QuranInfo(MadaniDataSource())
    builder = BrowsableSurahBuilder(
      appContext = context,
      pageProvider = fakePageProvider,
      audioExtensionDecider = fakeAudioExtensionDecider,
      qariArtworkProvider = QariArtworkProvider(context),
      recentQariManager = recentQariManager,
      quranInfo = quranInfo,
      currentQariManager = currentQariManager,
    )
  }

  @Test
  fun `root has 3 children when recents are empty`() = runTest {
    val children = builder.children(BrowsableSurahBuilder.ROOT_ID)
    assertThat(children).hasSize(3)
    assertThat(children[0].mediaId).isEqualTo(BrowsableSurahBuilder.SURAHS_ID)
    assertThat(children[1].mediaId).isEqualTo(BrowsableSurahBuilder.QARI_ID)
    assertThat(children[2].mediaId).isEqualTo(BrowsableSurahBuilder.JUZ_ID)
  }

  @Test
  fun `root has 4 children when recents are non-empty`() = runTest {
    recentQariManager.recordQari(qariId = 0, sura = 36)
    val children = builder.children(BrowsableSurahBuilder.ROOT_ID)
    assertThat(children).hasSize(4)
    assertThat(children[0].mediaId).isEqualTo(BrowsableSurahBuilder.RECENT_ID)
    assertThat(children[1].mediaId).isEqualTo(BrowsableSurahBuilder.SURAHS_ID)
  }

  @Test
  fun `surahs tab returns 114 items using default qari`() = runTest {
    val children = builder.children(BrowsableSurahBuilder.SURAHS_ID)
    assertThat(children).hasSize(114)
    assertThat(children[0].mediaId).isEqualTo("sura_1_0")
    assertThat(children[113].mediaId).isEqualTo("sura_114_0")
  }

  @Test
  fun `reciters tab shows all reciters`() = runTest {
    val children = builder.children(BrowsableSurahBuilder.QARI_ID)
    assertThat(children).hasSize(3)
    assertThat(children[0].mediaId).isEqualTo("quran_0")
    assertThat(children[1].mediaId).isEqualTo("quran_1")
    assertThat(children[2].mediaId).isEqualTo("quran_2")
  }

  @Test
  fun `qari folder returns 114 suras`() = runTest {
    val children = builder.children("quran_1")
    assertThat(children).hasSize(114)
    assertThat(children[0].mediaId).isEqualTo("sura_1_1")
  }

  @Test
  fun `juz tab returns 30 folders`() = runTest {
    val children = builder.children(BrowsableSurahBuilder.JUZ_ID)
    assertThat(children).hasSize(30)
    assertThat(children[0].mediaId).isEqualTo("juz_1")
    assertThat(children[29].mediaId).isEqualTo("juz_30")
  }

  @Test
  fun `juz 1 contains suras 1 and 2`() = runTest {
    val children = builder.children("juz_1")
    val suraNumbers = children.map {
      it.mediaId.split("_")[1].toInt()
    }
    assertThat(suraNumbers).isEqualTo(listOf(1, 2))
  }

  @Test
  fun `juz 30 contains suras 78 through 114`() = runTest {
    val children = builder.children("juz_30")
    val suraNumbers = children.map {
      it.mediaId.split("_")[1].toInt()
    }
    assertThat(suraNumbers.first()).isEqualTo(78)
    assertThat(suraNumbers.last()).isEqualTo(114)
    assertThat(suraNumbers).hasSize(114 - 78 + 1)
  }

  @Test
  fun `search finds surahs by name`() = runTest {
    val results = builder.search("Baqarah")
    assertThat(results).isNotEmpty()
    val suraResult = results.first { it.mediaId.startsWith("sura_") }
    assertThat(suraResult.mediaId).isEqualTo("sura_2_0")
  }

  @Test
  fun `search finds reciters by name`() = runTest {
    val results = builder.search("Husary")
    assertThat(results).isNotEmpty()
    val qariResult = results.first { it.mediaId.startsWith("quran_") }
    assertThat(qariResult.mediaId).isEqualTo("quran_1")
  }

  @Test
  fun `search with no matches returns empty list`() = runTest {
    val results = builder.search("xyznonexistent")
    assertThat(results).isEmpty()
  }

  @Test
  fun `gapless expand returns 114 sura items`() = runTest {
    val items = builder.expandMediaItem("sura_1_0")
    assertThat(items).hasSize(114)
    assertThat(items[0].mediaId).isEqualTo("sura_1_0")
    assertThat(items[0].localConfiguration?.uri).isNotNull()
  }

  @Test
  fun `gapped expand returns per-ayah items for one surah`() = runTest {
    // Al-Fatiha has 7 ayahs
    val items = builder.expandMediaItem("sura_1_2")
    assertThat(items).hasSize(7)
    assertThat(items[0].mediaId).isEqualTo("ayah_1_1_2")
    assertThat(items[6].mediaId).isEqualTo("ayah_1_7_2")
    assertThat(items[0].localConfiguration?.uri).isNotNull()
  }

  @Test
  fun `gapped surah media item has no URI`() = runTest {
    val item = builder.child("sura_1_2")
    assertThat(item).isNotNull()
    assertThat(item!!.localConfiguration?.uri).isNull()
  }

  @Test
  fun `gapless surah media item has URI`() = runTest {
    val item = builder.child("sura_1_0")
    assertThat(item).isNotNull()
    assertThat(item!!.localConfiguration?.uri).isNotNull()
  }

  @Test
  fun `child resolves ayah prefixed media ids`() = runTest {
    val item = builder.child("ayah_1_3_2")
    assertThat(item).isNotNull()
    assertThat(item!!.mediaId).isEqualTo("ayah_1_3_2")
    assertThat(item.localConfiguration?.uri).isNotNull()
    assertThat(item.localConfiguration?.uri.toString())
      .contains("001003.mp3")
  }

  @Test
  fun `surahs and juz tabs use default qari`() = runTest {
    currentQariManager.setCurrentQari(1)
    val surahsChildren = builder.children(BrowsableSurahBuilder.SURAHS_ID)
    assertThat(surahsChildren[0].mediaId).isEqualTo("sura_1_1")

    val juzChildren = builder.children("juz_1")
    assertThat(juzChildren[0].mediaId).isEqualTo("sura_1_1")
  }
}
