package no.nav.eessi.pensjon.kodeverk

import no.nav.eessi.pensjon.logging.RequestIdHeaderInterceptor
import no.nav.eessi.pensjon.logging.RequestResponseLoggerInterceptor
import no.nav.eessi.pensjon.shared.retry.IOExceptionRetryInterceptor
import no.nav.security.token.support.client.core.ClientProperties
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
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


@Configuration
@Profile("!excludeKodeverk")
class KodeverkRestTemplateConfig(
    private val clientConfigurationProperties: ClientConfigurationProperties,
    private val oAuth2AccessTokenService: OAuth2AccessTokenService?,
    @Autowired private val env: Environment
) {

    @Value("\${KODEVERK_URL}")
    lateinit var kodeverkUrl: String

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

        //Det er kun prod som trenger auth token
        if(env.activeProfiles[0] == "prod") {
            template.additionalInterceptors(
                interceptors.plus(
                    bearerTokenInterceptor(
                        clientConfigurationProperties.registration["kodeverk-credentials"]
                            ?: throw RuntimeException("could not find oauth2 client config for ${"kodeverk-credentials"}"),
                        oAuth2AccessTokenService!!
                    ))
            )
        }
        else{
            template.additionalInterceptors(interceptors)
        }

        return template.build().apply {
            requestFactory = BufferingClientHttpRequestFactory(
                SimpleClientHttpRequestFactory()
            )
        }
    }


    private fun bearerTokenInterceptor(
        clientProperties: ClientProperties,
        oAuth2AccessTokenService: OAuth2AccessTokenService
    ): ClientHttpRequestInterceptor {
        return ClientHttpRequestInterceptor { request: HttpRequest, body: ByteArray?, execution: ClientHttpRequestExecution ->
            val response = oAuth2AccessTokenService.getAccessToken(clientProperties)
            request.headers.setBearerAuth(response.access_token!!)
            execution.execute(request, body!!)
        }
    }

}
