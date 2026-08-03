package no.nav.eessi.pensjon.kodeverk

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.Metrics
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
        val postnummerKodeverkAPI = try {
            kodeVerkHentLandkoder.hentPostSted(postnummer)
        } catch (ex: KodeverkException) {
            if (postnummerLokalt == null) {
                logger.error("Feil ved henting av poststed fra kodeverk for postnummer $postnummer, og ingen lokal verdi finnes", ex)
                throw ex
            }
            logger.error("Feil ved henting av poststed fra kodeverk for postnummer $postnummer, bruker lokalt poststed $postnummerLokalt", ex)
            null
        }

        return if (postnummerLokalt == null) {
            logger.info("Lokalt poststed ikke funnet, bruker kodeverk for postnummer $postnummer")
            postnummerKodeverkAPI
        }
        else if (postnummerLokalt != postnummerKodeverkAPI?.sted) {
            logger.error("Forskjell mellom lokalt og kodeverk for postnummer $postnummer: fra fil=$postnummerLokalt, fra kodeverk=${postnummerKodeverkAPI?.sted}")
            Postnummer(postnummer, postnummerLokalt) // stoler mer på lokalt poststed
        } else {
            logger.info("Fant poststed for postnummer $postnummerKodeverkAPI")
            postnummerKodeverkAPI
        }
    }
    fun hentLandkoderAlpha2() = kodeVerkHentLandkoder.hentLandKoder().map { it.landkode2 }

    fun finnLandkode(landkode: String): String? {

        if (landkode.isEmpty() || landkode.length !in 2..3) {
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

    private var kodeverkMetrics: MetricsHelper.Metric
    private var kodeverkPostMetrics: MetricsHelper.Metric

    private val logger = LoggerFactory.getLogger(javaClass)
    private val postnummerRegisterLock = Any()

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

        return hentPostnummerRegister()[postnummer]
    }

    /**
     * Henter og cacher hele postnummerregisteret som én cache-oppføring, i stedet for én oppføring per postnummer.
     * Bruker dobbel-sjekket låsing for å unngå at flere samtidige kall ved cache-miss utløser flere separate
     * kall mot Kodeverk-APIet for det samme registeret.
     */
    private fun hentPostnummerRegister(): Map<String, Postnummer> {
        kodeverkCacheManager.getCache(KODEVERK_POSTNR_CACHE)?.get(POSTNUMMER_REGISTER_KEY, PostnummerRegister::class.java)?.let {
            logger.info("Postnummerregister hentet fra cache")
            Metrics.counter("ep_kodeverk_postnummer", "melding", "hentet_fra_cache").increment()
            return it.postnumre
        }

        synchronized(postnummerRegisterLock) {
            kodeverkCacheManager.getCache(KODEVERK_POSTNR_CACHE)?.get(POSTNUMMER_REGISTER_KEY, PostnummerRegister::class.java)?.let {
                logger.info("Postnummerregister hentet fra cache")
                Metrics.counter("ep_kodeverk_postnummer", "melding", "hentet_fra_cache").increment()
                return it.postnumre
            }

            return kodeverkPostMetrics.measure {
                val kodeverk = hentKodeverk("Postnummer")
                val postnummerRegister = mapJsonToAny<KodeverkResponse>(kodeverk).betydninger.entries.associate { (key, value) ->
                    key to Postnummer(key, value.firstOrNull()?.beskrivelser?.nb?.term ?: "UKJENT")
                }

                logger.info("Har importert postnummer og sted. size: ${postnummerRegister.size}")

                kodeverkCacheManager.getCache(KODEVERK_POSTNR_CACHE)?.put(POSTNUMMER_REGISTER_KEY, PostnummerRegister(postnummerRegister))

                Metrics.counter("ep_kodeverk_postnummer", "melding", "hentet_fra_kodeverk").increment()
                postnummerRegister
            }
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
            val response = kodeverkRestTemplate.exchange<String>(
                builder.toUriString(),
                HttpMethod.GET,
                requestEntity,
                String::class.java
            )

            val body = response.body
            if (body == null) {
                logger.error("Tomt svar (body er null) fra kodeverk for URI ${builder.toUriString()}")
                throw KodeverkException("Feil ved konvetering av jsondata fra kodeverk")
            }
            logger.info("KodeverkClient; response : $body")
            body

        } catch (ce: HttpClientErrorException) {
            logger.error(ce.message, ce)
            throw KodeverkException(ce.message ?: "Feil ved kall mot kodeverk (klientfeil)")
        } catch (se: HttpServerErrorException) {
            logger.error(se.message, se)
            throw KodeverkException(se.message ?: "Feil ved kall mot kodeverk (serverfeil)")
        } catch (ke: KodeverkException) {
            throw ke
        } catch (ex: Exception) {
            logger.error(ex.message, ex)
            throw KodeverkException(ex.message ?: "Ukjent feil ved kall mot kodeverk")
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

