package no.nav.eessi.pensjon.kodeverk

import no.nav.common.token_client.builder.AzureAdTokenClientBuilder
import no.nav.common.token_client.client.AzureAdOnBehalfOfTokenClient
import no.nav.eessi.pensjon.logging.RequestIdHeaderInterceptor
import no.nav.eessi.pensjon.logging.RequestResponseLoggerInterceptor
import no.nav.eessi.pensjon.shared.retry.IOExceptionRetryInterceptor
import no.nav.security.token.support.core.context.TokenValidationContextHolder
import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.security.token.support.core.jwt.JwtTokenClaims
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.http.HttpRequest
import org.springframework.http.client.BufferingClientHttpRequestFactory
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.DefaultResponseErrorHandler
import org.springframework.web.client.RestTemplate
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*

@Configuration
@Profile("!excludeKodeverk")
class KodeverkRestTemplateConfig(
    private val tokenValidationContextHolder: TokenValidationContextHolder,
    @Autowired private val env: Environment
) {
    private val logger = LoggerFactory.getLogger(KodeverkRestTemplateConfig::class.java)

    @Value("\${KODEVERK_URL}")
    lateinit var kodeverkUrl: String

    @Value("\${AZURE_APP_KODEVERK_CLIENT_ID}")
    lateinit var kodeverkClientId: String

    @Bean
    fun kodeverkRestTemplate(): RestTemplate {
        val template = RestTemplateBuilder()
            .rootUri(kodeverkUrl)
            .errorHandler(DefaultResponseErrorHandler())

        val interceptors = listOf(
            RequestIdHeaderInterceptor(),
            IOExceptionRetryInterceptor(),
            RequestResponseLoggerInterceptor()
        )
        template.additionalInterceptors(
            interceptors.plus(onBehalfOfBearerTokenInterceptor(kodeverkClientId))
        )

        return template.build().apply {
            requestFactory = BufferingClientHttpRequestFactory(
                SimpleClientHttpRequestFactory()
            )
        }
    }

    private fun onBehalfOfBearerTokenInterceptor(clientId: String): ClientHttpRequestInterceptor {
        logger.info("init onBehalfOfBearerTokenInterceptor kodeverk: $clientId")
        return ClientHttpRequestInterceptor { request: HttpRequest, body: ByteArray?, execution: ClientHttpRequestExecution ->
            val decodedToken = URLDecoder.decode(getToken(tokenValidationContextHolder).encodedToken, StandardCharsets.UTF_8)

            logger.debug("NAVIdent: ${getClaims(tokenValidationContextHolder).get("NAVident")?.toString()}")

            val tokenClient: AzureAdOnBehalfOfTokenClient = AzureAdTokenClientBuilder.builder()
                .withNaisDefaults()
                .buildOnBehalfOfTokenClient()

            val accessToken: String = tokenClient.exchangeOnBehalfOfToken(
                "api://$clientId/.default",
                decodedToken
            )
            logger.debug("Access token: $accessToken")

            request.headers.setBearerAuth(accessToken)
            execution.execute(request, body!!)
        }
    }

    fun getClaims(tokenValidationContextHolder: TokenValidationContextHolder): JwtTokenClaims {
        val context = tokenValidationContextHolder.getTokenValidationContext()
        if (context.issuers.isEmpty())
            throw RuntimeException("No issuer found in context")

        val validIssuer = context.issuers.filterNot { issuer ->
            val oidcClaims = context.getClaims(issuer)
            oidcClaims.expirationTime.before(Date())
        }.map { it }


        if (validIssuer.isNotEmpty()) {
            val issuer = validIssuer.first()
            return context.getClaims(issuer)
        }
        throw RuntimeException("No valid issuer found in context")

    }

    fun getToken(tokenValidationContextHolder: TokenValidationContextHolder): JwtToken {
        val context = tokenValidationContextHolder.getTokenValidationContext()
        if (context.issuers.isEmpty())
            throw RuntimeException("No issuer found in context")
        val issuer = context.issuers.first()

        return context.getJwtToken(issuer)!!

    }
}
