package com.quran.labs.feature.autoquran.di

import com.quran.data.di.AppScope
import com.quran.labs.feature.autoquran.common.QariArtworkContentProvider
import dev.zacsweers.metro.ContributesTo

@ContributesTo(AppScope::class)
interface QuranAutoInjector {
  fun inject(provider: QariArtworkContentProvider)
}
