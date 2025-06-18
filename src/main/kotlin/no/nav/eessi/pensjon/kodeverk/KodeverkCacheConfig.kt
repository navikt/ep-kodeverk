package no.nav.eessi.pensjon.kodeverk

import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

internal const val KODEVERK_CACHE = "kodeverk"
internal const val KODEVERK_POSTNR_CACHE = "kodeverk_postnr"

@Configuration
@EnableCaching
class KodeverkCacheConfig {

    @Bean("kodeverkCacheManager")
    fun kodeverkCacheManager(): ConcurrentMapCacheManager {
        return ConcurrentMapCacheManager(KODEVERK_CACHE, KODEVERK_POSTNR_CACHE)
    }
}