package no.nav.eessi.pensjon.kodeverk

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.nav.eessi.pensjon.metrics.MetricsHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.cache.CacheManager
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.web.client.RestTemplate


private const val LANDKODE_URL = "/api/v1/hierarki/LandkoderSammensattISO2/noder"

@SpringJUnitConfig(classes = [KodeverkCacheConfig::class, KodeVerkHentLandkoderTest.Config::class])
class KodeVerkHentLandkoderTest {

    @Autowired
    lateinit var kodeverkClient: KodeverkClient

    @BeforeEach
    fun setup() {
        every {
            restTemplate
            .exchange(
                eq(LANDKODE_URL),
                any(),
                any<HttpEntity<Unit>>(),
                eq(String::class.java)
            )
        } returns ResponseEntity("", HttpStatus.OK)
    }

    @Test
    fun `kodeverk skal cache henting av landkoder`() {
        kodeverkClient.hentAlleLandkoder()
        kodeverkClient.hentAlleLandkoder()

        verify (exactly = 1) { restTemplate
            .exchange(
                eq(LANDKODE_URL),
                any(),
                any<HttpEntity<Unit>>(),
                eq(String::class.java)
            )
        }
    }

    companion object {
        var restTemplate: RestTemplate = mockk()
    }

    @TestConfiguration
    class Config {

        @Bean("kodeverkCacheManager")
        fun cacheManager(): CacheManager {
            return ConcurrentMapCacheManager(KODEVERK_CACHE, KODEVERK_POSTNR_CACHE)
        }

        @Bean
        fun kodeVerkHentLandkoder(): KodeVerkHentLandkoder {
            return KodeVerkHentLandkoder("testApp", restTemplate, MetricsHelper.ForTest())
        }

        @Bean
        fun postnummerService(): PostnummerService {
            return PostnummerService(MetricsHelper.ForTest())
        }

        @Bean
        fun kodeverkClient(): KodeverkClient {
            return KodeverkClient(kodeVerkHentLandkoder(), postnummerService())
        }
    }
}