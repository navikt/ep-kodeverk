package no.nav.eessi.pensjon.kodeverk

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.Metrics.counter
import no.nav.eessi.pensjon.logging.RequestIdOnMDCFilter
import no.nav.eessi.pensjon.metrics.MetricsHelper
import no.nav.eessi.pensjon.utils.mapJsonToAny
import no.nav.eessi.pensjon.utils.toJson
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestTemplate
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.util.UriComponents
import org.springframework.web.util.UriComponentsBuilder
import java.util.*

@Component
@Profile("!excludeKodeverk")
class KodeverkClient(
    @Autowired private val kodeVerkHentLandkoder: KodeVerkHentLandkoder, private val postnummerService: PostnummerService)
{
    private val logger = LoggerFactory.getLogger(KodeverkClient::class.java)

    fun hentAlleLandkoder() = kodeVerkHentLandkoder.hentLandKoder().toJson()

    fun hentPostSted(postnummer: String?): Postnummer? {
        if (postnummer.isNullOrEmpty()) {
            logger.warn("Postnummer er null eller tomt")
            return null
        }

        val postnummerLokalt = postnummerService.finnPoststed(postnummer)
        val postnummerKodeverkAPI = kodeVerkHentLandkoder.hentPostSted(postnummer)

        return if (postnummerLokalt != postnummerKodeverkAPI?.sted) {
            logger.error("Forskjell mellom lokalt og kodeverk for postnummer $postnummer: V1=$postnummerLokalt, V2=${postnummerKodeverkAPI?.sted}")
            postnummerLokalt?.let { Postnummer(postnummer, it) } // stoler med på lokalt poststed
        } else {
            logger.info("Fant poststed for postnummer $postnummerKodeverkAPI")
            postnummerKodeverkAPI
        }
    }
    fun hentLandkoderAlpha2() = kodeVerkHentLandkoder.hentLandKoder().map { it.landkode2 }

    fun finnLandkode(landkode: String): String? {

        if (landkode.isNullOrEmpty() || landkode.length !in 2..3) {
            throw LandkodeException("Ugyldig landkode: $landkode")
        }
        return when (landkode.length) {
            2 -> kodeVerkHentLandkoder.hentLandKoder().firstOrNull { it.landkode2 == landkode }?.landkode3
            3 -> kodeVerkHentLandkoder.hentLandKoder().firstOrNull { it.landkode3 == landkode }?.landkode2
            else -> throw LandkodeException("Ugyldig landkode: $landkode")
        }.also { landkode -> logger.debug("landkode $landkode") }
    }

    companion object{
        fun mapperWithJavaTime(): ObjectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
    }
}

data class Landkode(
    val landkode2: String, // SE
    val landkode3: String // SWE
)

class KodeverkException(message: String) : ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message)
class LandkodeException(message: String) : ResponseStatusException(HttpStatus.BAD_REQUEST, message)

/**
    Deler av koden nedenfor er hentet fra: https://github.com/navikt/samordning-personoppslag/tree/main
 */
@Component
@Profile("!excludeKodeverk")
class KodeVerkHentLandkoder(
    @Value("\${NAIS_APP_NAME}") val appName: String,
    private val kodeverkRestTemplate: RestTemplate,
    private var kodeverkCacheManager: ConcurrentMapCacheManager,
    @Autowired(required = false) private val metricsHelper: MetricsHelper = MetricsHelper.ForTest()
) {

    private lateinit var kodeverkMetrics: MetricsHelper.Metric
    private lateinit var kodeverkPostMetrics: MetricsHelper.Metric

    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        kodeverkMetrics = metricsHelper.init("KodeverkHentLandKode")
        kodeverkPostMetrics = metricsHelper.init("KodeverkHentPostnr")
    }
    @Cacheable(cacheNames = [KODEVERK_CACHE], key = "#root.methodName", cacheManager = "kodeverkCacheManager")
    fun hentLandKoder(): List<Landkode> {
        return kodeverkMetrics.measure {
            val rootNode = jacksonObjectMapper().readTree(hentHierarki("LandkoderSammensattISO2"))
            val noder = rootNode.at("/noder").toList()

            noder.map { node ->
                Landkode(
                    node.at("/kode").textValue(),
                    node.at("/undernoder").findPath("kode").textValue()
                )
            }.sortedBy { (sorting, _) -> sorting }.toList().also {
                logger.info("Har importert landkoder")
            }
        }
    }

    fun hentPostSted(postnummer: String?): Postnummer? {
        logger.info("Henter postSted for postnummer: $postnummer")
        if (postnummer.isNullOrEmpty()) {
            logger.warn("Postnummer er null eller tomt")
            return null
        }

        val cachedPostnummer1 = kodeverkCacheManager.getCache(KODEVERK_POSTNR_CACHE)?.get(postnummer, Postnummer::class.java)
        if (cachedPostnummer1 != null) {
            logger.info("Postnummer hentet fra cache: $postnummer")
            counter("ep_kodeverk_postnummer", "melding", "hentet_fra_cache").increment()
        }
        val cachedPostnummer = cachedPostnummer1
        if (cachedPostnummer != null) {
            return cachedPostnummer
        }

        return kodeverkPostMetrics.measure {
            val kodeverk = hentKodeverk("Postnummer")
            val postnummerList = mapJsonToAny<KodeverkResponse>(kodeverk).betydninger.map {
                Postnummer(it.key, it.value.firstOrNull()?.beskrivelser?.nb?.term ?: "UKJENT")
            }.sortedBy { it.postnummer }

            logger.info("Har importert postnummer og sted. size: ${postnummerList.size}")

            postnummerList.forEach { entry ->
                kodeverkCacheManager.getCache(KODEVERK_POSTNR_CACHE)?.put(entry.postnummer, entry)
            }

            Metrics.counter("ep_kodeverk_postnummer", "melding", "hentet_fra_kodeverk").increment()
            postnummerList.firstOrNull { it.postnummer == postnummer }
        }
    }

    private fun hentKodeverk(kodeverk: String): String {
        val path = "/api/v1/kodeverk/{kodeverk}/koder/betydninger?spraak=nb"
        val uriParams = mapOf("kodeverk" to kodeverk)

        return doRequest(UriComponentsBuilder.fromUriString(path).buildAndExpand(uriParams))
    }

    private fun doRequest(builder: UriComponents): String {
        return try {
            val headers = HttpHeaders()
            headers["Nav-Consumer-Id"] = appName
            headers["Nav-Call-Id"] =  MDC.get(RequestIdOnMDCFilter.REQUEST_ID_MDC_KEY) ?: UUID.randomUUID().toString()
            val requestEntity = HttpEntity<String>(headers)
            logger.info("Header: $requestEntity")
            kodeverkRestTemplate.exchange<String>(
                builder.toUriString(),
                HttpMethod.GET,
                requestEntity,
                String::class.java
            ).body ?: throw KodeverkException("Feil ved konvetering av jsondata fra kodeverk")
                .also { logger.info("KodeverkClient; response : $it") }

        } catch (ce: HttpClientErrorException) {
            logger.error(ce.message, ce)
            throw KodeverkException(ce.message!!)
        } catch (se: HttpServerErrorException) {
            logger.error(se.message, se)
            throw KodeverkException(se.message!!)
        } catch (ex: Exception) {
            logger.error(ex.message, ex)
            throw KodeverkException(ex.message!!)
        }
    }

    /**
     *  https://kodeverk.nais.adeo.no/api/v1/hierarki/LandkoderSammensattISO2/noder
     */
    private fun hentHierarki(hierarki: String): String {
        val path = "/api/v1/hierarki/{hierarki}/noder"

        val uriParams = mapOf("hierarki" to hierarki)
        val builder = UriComponentsBuilder.fromUriString(path).buildAndExpand(uriParams)

        return doRequest(builder)
    }
}

