package no.nav.eessi.pensjon.kodeverk

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class KodeverkResponse(
    val betydninger: Map<String, List<KodeverkBetydning>>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KodeverkBetydning(
    val beskrivelser: KodeverkBeskrivelser
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KodeverkBeskrivelser (
    val nb: KodeverkTerm
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KodeverkTerm (
    val term: String
)

data class Postnummer(
    val postnummer: String,
    val sted: String
)

/**
 * Wrapper rundt hele postnummerregisteret slik at det kan caches som én oppføring (i stedet for én oppføring
 * per postnummer), og hentes ut igjen med et rent, ikke-generisk typecast via [org.springframework.cache.Cache.get].
 */
internal data class PostnummerRegister(
    val postnumre: Map<String, Postnummer>
)