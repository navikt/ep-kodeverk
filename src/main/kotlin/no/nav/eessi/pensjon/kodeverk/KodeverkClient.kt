package no.nav.eessi.pensjon.kodeverk

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.nav.eessi.pensjon.metrics.MetricsHelper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.Cacheable
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
    @Autowired private val kodeVerkHentLandkoder: KodeVerkHentLandkoder)
{
    fun hentAlleLandkoder() = kodeVerkHentLandkoder.hentLandKoder().toJson()

    fun hentLandkoderAlpha2() = kodeVerkHentLandkoder.hentLandKoder().map { it.landkode2 }

    fun finnLandkode(landkode: String): String? {

        if (landkode.isNullOrEmpty() || landkode.length !in 2..3) {
            throw LandkodeException("Ugyldig landkode: $landkode")
        }
        return when (landkode.length) {
            2 -> kodeVerkHentLandkoder.hentLandKoder().firstOrNull { it.landkode2 == landkode }?.landkode3
            3 -> kodeVerkHentLandkoder.hentLandKoder().firstOrNull { it.landkode3 == landkode }?.landkode2
            else -> throw LandkodeException("Ugyldig landkode: $landkode")
        }
    }

    companion object{
        fun mapAnyToJson(data: Any): String {
            return mapperWithJavaTime()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(data)
        }

        fun mapperWithJavaTime(): ObjectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())

        fun Any.toJson() = mapAnyToJson(this)
    }
}

data class Landkode(
    val landkode2: String, // SE
    val landkode3: String // SWE
)

class KodeverkException(message: String) : ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message)
class LandkodeException(message: String) : ResponseStatusException(HttpStatus.BAD_REQUEST, message)


@Component
@Profile("!excludeKodeverk")
class KodeVerkHentLandkoder(
    @Value("\${NAIS_APP_NAME}") val appName: String,
    private val kodeverkRestTemplate: RestTemplate,
    @Autowired(required = false) private val metricsHelper: MetricsHelper = MetricsHelper.ForTest()
) {
    lateinit var kodeverkMetrics: MetricsHelper.Metric

    private val logger = LoggerFactory.getLogger(KodeVerkHentLandkoder::class.java)

    init {
        kodeverkMetrics = metricsHelper.init("KodeverkHentLandKode")
    }
    @Cacheable(cacheNames = [KODEVERK_CACHE], key = "#root.methodName", cacheManager = "kodeverkCacheManager")
    fun hentLandKoder(): List<Landkode> {
        return kodeverkMetrics.measure {

            val tmpLandkoder = hentHierarki("LandkoderSammensattISO2")

            val rootNode = jacksonObjectMapper().readTree(tmpLandkoder)
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

    private fun doRequest(builder: UriComponents): String {
        try {
            val headers = HttpHeaders()
            headers["Nav-Consumer-Id"] = appName
            headers["Nav-Call-Id"] = UUID.randomUUID().toString()
            val requestEntity = HttpEntity<String>(headers)
            logger.debug("Header: $requestEntity")
            val response = kodeverkRestTemplate.exchange(
                builder.toUriString(),
                HttpMethod.GET,
                requestEntity,
                String::class.java
            ).also { logger.info("KodeverkClient; response : $it") }

            return response.body ?: throw KodeverkException("Feil ved konvetering av jsondata fra kodeverk")

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

