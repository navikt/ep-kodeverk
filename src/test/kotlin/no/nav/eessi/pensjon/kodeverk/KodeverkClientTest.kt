package no.nav.eessi.pensjon.kodeverk

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.nav.eessi.pensjon.metrics.MetricsHelper
import no.nav.eessi.pensjon.utils.toJson
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.cache.concurrent.ConcurrentMapCache
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.web.client.RestTemplate
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

@SpringJUnitConfig(classes = [KodeverkCacheConfig::class, KodeverkClientTest.Config::class])
class KodeverkClientTest {

    @Autowired
    lateinit var kodeverkCacheManager: ConcurrentMapCacheManager

    @Autowired
    private lateinit var kodeverkClient: KodeverkClient

    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val kodeverkPostnrResponse = mapper.readValue<KodeverkResponse>(javaClass.getResource("/postnummer.json")?.readText()
        ?: throw Exception("ikke funnet"))

    companion object {
        var mockrestTemplate: RestTemplate = mockk()
        @JvmStatic
        fun landkoder() = Stream.of(
            Arguments.of("SE", "SWE"), // landkode 2
            Arguments.of("BMU", "BM"), // landkode 3
            Arguments.of("ALB", "AL"), // landkode 3
        )
    }

    @TestConfiguration
    class Config {

        @Bean("kodeverkCacheManager")
        fun cacheManager(): ConcurrentMapCacheManager {
            return ConcurrentMapCacheManager(KODEVERK_CACHE, KODEVERK_POSTNR_CACHE)
        }

        @Bean
        fun kodeVerkHentLandkoder(): KodeVerkHentLandkoder {
            return KodeVerkHentLandkoder( "eessi-fagmodul", mockrestTemplate,  cacheManager(), MetricsHelper.ForTest())
        }

        @Bean
        fun postnummerService() = PostnummerService()

        @Bean
        fun kodeverkClient(): KodeverkClient {
            return KodeverkClient(kodeVerkHentLandkoder = kodeVerkHentLandkoder(), postnummerService = postnummerService())
        }
    }

    @AfterEach
    fun takeDown() {
        clearMocks(mockrestTemplate)
    }

    @BeforeEach
    fun setup() {
        val mockResponseEntityISO3 =
            createResponseEntityFromJsonFile("src/test/resources/no/nav/eessi/pensjon/kodeverk/landkoderSammensattIso2.json")

        val betydninger_spraak_nb =
            createResponseEntityFromJsonFile("src/test/resources/betydninger_spraak_nb")

        every {
            mockrestTemplate
                .exchange(
                    eq("/api/v1/hierarki/LandkoderSammensattISO2/noder"),
                    any(),
                    any<HttpEntity<Unit>>(),
                    eq(String::class.java)
                )
        } returns mockResponseEntityISO3

        every {
            mockrestTemplate
                .exchange(
                    eq("/api/v1/kodeverk/Postnummer/koder/betydninger?spraak=nb"),
                    HttpMethod.GET,
                    any<HttpEntity<Unit>>(),
                    eq(String::class.java)
                )
        } returns betydninger_spraak_nb
    }

    @ParameterizedTest(name = "Henter landkode: {0}. Forventet svar: {1}")
    @MethodSource("landkoder")
    fun `henting av landkode ved bruk av landkode `(expected: String, landkode: String) {

        val actual = kodeverkClient.finnLandkode(landkode)
        assertEquals(expected, actual)
    }

    @ParameterizedTest
    @CsvSource("4971, SUNDEBRU, 1", "2306, HAMAR, 2", "3630, RØDBERG, 3", "3631, RØDBERG, 4", "3632, UVDAL, 5","3632, UVDAL, 5", "3632, UVDAL, 5")
    fun `henter poststed fra forskjellige metoder for cahce-testing`(postnummer: String, sted: String, testRun: Int) {
        if(testRun == 1) kodeverkCacheManager.getCache(KODEVERK_POSTNR_CACHE)?.clear()

        assertEquals(sted, kodeverkClient.hentPostSted(postnummer)?.sted)
        verify(exactly = if (testRun == 1) 1 else 0) {
            mockrestTemplate.exchange(eq("/api/v1/kodeverk/Postnummer/koder/betydninger?spraak=nb"), eq(HttpMethod.GET), any<HttpEntity<Unit>>(), any<Class<String>>())
        }
    }

    private fun cache(): List<MutableMap.MutableEntry<in Any, in Any>> {
        val cache = kodeverkCacheManager.getCache(KODEVERK_POSTNR_CACHE) as ConcurrentMapCache
        return cache.nativeCache.entries.toList()
    }

    @Test
    fun `hentpostnummer skal være likt for gammel og ny metode`() {
        every { mockrestTemplate.exchange(
            eq("/api/v1/kodeverk/Postnummer/koder/betydninger?spraak=nb"),
            any(),
            any<HttpEntity<Unit>>(),
            eq(String::class.java)
        ) }  returns ResponseEntity<String>(kodeverkPostnrResponse.toJson(), HttpStatus.OK)

        val poststed = kodeverkClient.hentPostSted("3650")
        assertEquals("TINN AUSTBYGD", poststed?.sted)
    }

    @Test
    fun `hentpostnummer skal velge gammel metode ved feil`() {
        every { mockrestTemplate.exchange(
            eq("/api/v1/kodeverk/Postnummer/koder/betydninger?spraak=nb"),
            any(),
            any<HttpEntity<Unit>>(),
            eq(String::class.java)
        ) }  returns ResponseEntity<String>(kodeverkPostnrResponse.toJson(), HttpStatus.OK)

        val poststed = kodeverkClient.hentPostSted("5786")
        assertEquals("EIDFJORD", poststed?.sted)
    }

    @Test
    fun `kodeverk call postnr return poststed`() {

        every { mockrestTemplate.exchange(
            eq("/api/v1/kodeverk/Postnummer/koder/betydninger?spraak=nb"),
            any(),
            any<HttpEntity<Unit>>(),
            eq(String::class.java)
        ) }  returns ResponseEntity<String>(kodeverkPostnrResponse.toJson(), HttpStatus.OK)

        val result = kodeverkClient.hentPostSted("2320")
        assertEquals("2320", result?.postnummer)
        assertEquals("FURNES", result?.sted)
    }

    @Test
    fun `hentPostSted faller tilbake til lokalt poststed når kodeverk-kallet feiler`() {
        kodeverkCacheManager.getCache(KODEVERK_POSTNR_CACHE)?.clear()
        every { mockrestTemplate.exchange(
            eq("/api/v1/kodeverk/Postnummer/koder/betydninger?spraak=nb"),
            any(),
            any<HttpEntity<Unit>>(),
            eq(String::class.java)
        ) } throws org.springframework.web.client.HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR)

        // 3650 finnes i det lokale postnummerregisteret (TINN AUSTBYGD)
        val poststed = kodeverkClient.hentPostSted("3650")

        assertEquals("TINN AUSTBYGD", poststed?.sted)
    }

    @Test
    fun `hentPostSted kaster videre når kodeverk-kallet feiler og postnummer ikke finnes lokalt`() {
        kodeverkCacheManager.getCache(KODEVERK_POSTNR_CACHE)?.clear()
        every { mockrestTemplate.exchange(
            eq("/api/v1/kodeverk/Postnummer/koder/betydninger?spraak=nb"),
            any(),
            any<HttpEntity<Unit>>(),
            eq(String::class.java)
        ) } throws org.springframework.web.client.HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR)

        // Ukjent/ikke-eksisterende postnummer, finnes ikke lokalt
        assertThrows<KodeverkException> {
            kodeverkClient.hentPostSted("00000")
        }
    }

    @Test
    fun `hentPostSted returnerer null når postnummer ikke finnes verken lokalt eller i kodeverk`() {
        kodeverkCacheManager.getCache(KODEVERK_POSTNR_CACHE)?.clear()
        every { mockrestTemplate.exchange(
            eq("/api/v1/kodeverk/Postnummer/koder/betydninger?spraak=nb"),
            any(),
            any<HttpEntity<Unit>>(),
            eq(String::class.java)
        ) } returns ResponseEntity<String>(kodeverkPostnrResponse.toJson(), HttpStatus.OK)

        // "9999" finnes verken i postnummerregister.txt (lokalt) eller i postnummer.json-fixturen (kodeverk)
        val poststed = kodeverkClient.hentPostSted("9999")

        assertEquals(null, poststed)
    }

    @Test
    fun `postnummerregisteret caches som én samlet oppføring, ikke én per postnummer`() {
        kodeverkCacheManager.getCache(KODEVERK_POSTNR_CACHE)?.clear()
        every { mockrestTemplate.exchange(
            eq("/api/v1/kodeverk/Postnummer/koder/betydninger?spraak=nb"),
            any(),
            any<HttpEntity<Unit>>(),
            eq(String::class.java)
        ) } returns ResponseEntity<String>(kodeverkPostnrResponse.toJson(), HttpStatus.OK)

        kodeverkClient.hentPostSted("4971")

        val cacheEntries = cache()
        assertEquals(1, cacheEntries.size)
        assertEquals(POSTNUMMER_REGISTER_KEY, cacheEntries.first().key)
    }

    @Test
    fun `hentPostSted kaster KodeverkException når kodeverk svarer med tom body`() {
        kodeverkCacheManager.getCache(KODEVERK_POSTNR_CACHE)?.clear()
        every { mockrestTemplate.exchange(
            eq("/api/v1/kodeverk/Postnummer/koder/betydninger?spraak=nb"),
            any(),
            any<HttpEntity<Unit>>(),
            eq(String::class.java)
        ) } returns ResponseEntity<String>(null, HttpStatus.OK)

        // Postnummer finnes ikke lokalt, så det tomme kodeverk-svaret kan ikke reddes av lokal fallback
        val exception = assertThrows<KodeverkException> {
            kodeverkClient.hentPostSted("9999")
        }
        assertEquals("500 INTERNAL_SERVER_ERROR \"Feil ved konvetering av jsondata fra kodeverk\"", exception.message)
    }

    @Test
    fun `hentPostSted kaster KodeverkException med fallback-melding når underliggende feil mangler melding`() {
        kodeverkCacheManager.getCache(KODEVERK_POSTNR_CACHE)?.clear()
        every { mockrestTemplate.exchange(
            eq("/api/v1/kodeverk/Postnummer/koder/betydninger?spraak=nb"),
            any(),
            any<HttpEntity<Unit>>(),
            eq(String::class.java)
        ) } throws IllegalStateException(null as String?)

        // "9999" finnes ikke lokalt, så feilen kan ikke reddes av lokal fallback.
        // Uten fallback-meldingen ville dette kastet NullPointerException i stedet for KodeverkException (se message!!).
        val exception = assertThrows<KodeverkException> {
            kodeverkClient.hentPostSted("9999")
        }
        assertEquals("500 INTERNAL_SERVER_ERROR \"Ukjent feil ved kall mot kodeverk\"", exception.message)
    }

    @Test
    fun `hentPostSted henter postnummerregisteret kun én gang ved samtidige kall`() {
        kodeverkCacheManager.getCache(KODEVERK_POSTNR_CACHE)?.clear()
        every { mockrestTemplate.exchange(
            eq("/api/v1/kodeverk/Postnummer/koder/betydninger?spraak=nb"),
            any(),
            any<HttpEntity<Unit>>(),
            eq(String::class.java)
        ) } returns ResponseEntity<String>(kodeverkPostnrResponse.toJson(), HttpStatus.OK)

        val postnumre = listOf("4971", "2306", "2305", "2304", "2303")
        val executor = Executors.newFixedThreadPool(postnumre.size)
        val startSignal = CountDownLatch(1)

        val futures = postnumre.map { postnummer ->
            executor.submit<Postnummer?> {
                startSignal.await()
                kodeverkClient.hentPostSted(postnummer)
            }
        }
        startSignal.countDown()
        val results = futures.map { it.get(5, TimeUnit.SECONDS) }
        executor.shutdown()

        assertEquals(postnumre, results.map { it?.postnummer })
        verify(exactly = 1) {
            mockrestTemplate.exchange(eq("/api/v1/kodeverk/Postnummer/koder/betydninger?spraak=nb"), eq(HttpMethod.GET), any<HttpEntity<Unit>>(), any<Class<String>>())
        }
    }

    @Test
    fun hentingavIso2landkodevedbrukAvlandkode3FeilerMedNull() {
        val landkode2 = "BMUL"

        val exception = assertThrows<LandkodeException> {
            kodeverkClient.finnLandkode(landkode2)

        }
        assertEquals("400 BAD_REQUEST \"Ugyldig landkode: BMUL\"", exception.message)
    }

    inline fun <reified T : Any> mapJsonToAny(json: String): T {
        return KodeverkClient.mapperWithJavaTime()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .readValue(json, object : TypeReference<T>() {})
    }

    private fun createResponseEntityFromJsonFile(
        filePath: String,
        httpStatus: HttpStatus = HttpStatus.OK
    ): ResponseEntity<String> {
        val mockResponseString = String(Files.readAllBytes(Paths.get(filePath)))
        return ResponseEntity(mockResponseString, httpStatus)
    }
}