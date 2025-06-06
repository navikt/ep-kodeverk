package no.nav.eessi.pensjon.kodeverk

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import no.nav.eessi.pensjon.utils.toJson
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestTemplate
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Stream

class KodeverkClientTest {

    private val mockrestTemplate: RestTemplate = mockk()

    private lateinit var kodeverkService: KodeverkClient
    private lateinit var kodeverkClient: KodeVerkHentLandkoder

    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val kodeverkPostnrResponse = mapper.readValue<KodeverkResponse>(javaClass.getResource("/postnummer.json")?.readText()
        ?: throw Exception("ikke funnet"))


    @BeforeEach
    fun setup() {
        kodeverkClient = KodeVerkHentLandkoder( "eessi-fagmodul", mockrestTemplate)
        kodeverkService = KodeverkClient(kodeVerkHentLandkoder = kodeverkClient)

        val mockResponseEntityISO3 =
            createResponseEntityFromJsonFile("src/test/resources/no/nav/eessi/pensjon/kodeverk/landkoderSammensattIso2.json")

        every {
            mockrestTemplate
                .exchange(
                    eq("/api/v1/hierarki/LandkoderSammensattISO2/noder"),
                    any(),
                    any<HttpEntity<Unit>>(),
                    eq(String::class.java)
                )
        } returns mockResponseEntityISO3
    }

    @ParameterizedTest(name = "Henter landkode: {0}. Forventet svar: {1}")
    @MethodSource("landkoder")
    fun `henting av landkode ved bruk av landkode `(expected: String, landkode: String) {

        val actual = kodeverkService.finnLandkode(landkode)

        Assertions.assertEquals(expected, actual)
    }

    private companion object {
        @JvmStatic
        fun landkoder() = Stream.of(
            Arguments.of("SE", "SWE"), // landkode 2
            Arguments.of("BMU", "BM"), // landkode 3
            Arguments.of("ALB", "AL"), // landkode 3
        )
    }

    @Test
    fun testerLankodeMed2Siffer() {
        val actual = kodeverkService.hentLandkoderAlpha2()

        Assertions.assertEquals("ZW", actual.last())
        Assertions.assertEquals(249, actual.size)
    }

    @Test
    fun henteAlleLandkoderReturnererAlleLandkoder() {
        val json = kodeverkService.hentAlleLandkoder()

        val list = mapJsonToAny<List<Landkode>>(json)

        Assertions.assertEquals(249, list.size)

        Assertions.assertEquals("AD", list.first().landkode2)
        Assertions.assertEquals("AND", list.first().landkode3)
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
        Assertions.assertEquals("2320", result?.postnummer)
        Assertions.assertEquals("FURNES", result?.sted)

    }

    @Test
    fun hentingavIso2landkodevedbrukAvlandkode3FeilerMedNull() {
        val landkode2 = "BMUL"

        val exception = assertThrows<LandkodeException> {
            kodeverkService.finnLandkode(landkode2)

        }
        Assertions.assertEquals("400 BAD_REQUEST \"Ugyldig landkode: BMUL\"", exception.message)
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