package no.nav.eessi.pensjon.kodeverk

import no.nav.eessi.pensjon.logging.RequestIdHeaderInterceptor
import no.nav.eessi.pensjon.logging.RequestResponseLoggerInterceptor
import no.nav.eessi.pensjon.shared.retry.IOExceptionRetryInterceptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.client.BufferingClientHttpRequestFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.DefaultResponseErrorHandler
import org.springframework.web.client.RestTemplate


@Configuration
@Profile("!excludeKodeverk", "test")
class KodeverkRestTemplateConfigPreProd {

    @Value("\${KODEVERK_URL}")
    lateinit var kodeverkUrl: String

    @Bean
    fun kodeverkRestTemplate(): RestTemplate =
        RestTemplateBuilder()
            .rootUri(kodeverkUrl)
            .errorHandler(DefaultResponseErrorHandler())
            .additionalInterceptors(
                RequestIdHeaderInterceptor(),
                IOExceptionRetryInterceptor(),
                RequestResponseLoggerInterceptor(),
            )
            .build().apply {
                requestFactory = BufferingClientHttpRequestFactory(
                    SimpleClientHttpRequestFactory().apply { setOutputStreaming(false) }
                )
            }


}
