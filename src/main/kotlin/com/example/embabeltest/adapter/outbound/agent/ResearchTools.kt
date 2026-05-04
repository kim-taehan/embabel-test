package com.example.embabeltest.adapter.outbound.agent

import com.embabel.agent.api.annotation.LlmTool
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.Duration as JavaDuration

@Component
class ResearchTools(
    @param:Value("\${tavily.api-key:}") private val tavilyApiKey: String,
    @param:Value("\${tavily.base-url:https://api.tavily.com}") private val tavilyBaseUrl: String,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val webClient: WebClient = defaultWebClient(),
) {

    @LlmTool(
        name = "current_date",
        description = "Returns today's date in ISO-8601 format (YYYY-MM-DD). " +
            "Call this once and embed the result in the headline as 'as of YYYY-MM-DD'.",
    )
    fun currentDate(): String =
        LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE)

    @LlmTool(
        name = "tavily_search",
        description = "Web search optimized for LLM grounding. " +
            "Returns a list of up-to-date results, each with a title, url, and an extracted text snippet. " +
            "Call this BEFORE drafting keyPoints whenever the topic mentions temporal terms " +
            "('today', '오늘', 'recent', '최근', 'latest'), proper nouns you are not certain about, " +
            "or any time-sensitive fact. Cite the returned URLs inline in keyPoints.",
    )
    fun tavilySearch(
        @LlmTool.Param(description = "Concise search query in the language most likely to retrieve relevant sources (English usually best).")
        query: String,
        @LlmTool.Param(description = "Maximum number of results to return (1..10).", required = false)
        maxResults: Int = 5,
    ): List<TavilyResult> {
        check(tavilyApiKey.isNotBlank()) { "TAVILY_API_KEY is not configured" }
        val response = webClient.post()
            .uri("$tavilyBaseUrl/search")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                TavilyRequest(
                    apiKey = tavilyApiKey,
                    query = query,
                    maxResults = maxResults.coerceIn(1, 10),
                ),
            )
            .retrieve()
            .bodyToMono(TavilyResponse::class.java)
            .block(REQUEST_TIMEOUT) ?: return emptyList()
        return response.results.map { it.truncated() }
    }

    private fun TavilyResult.truncated(): TavilyResult =
        if (content.length <= MAX_CONTENT_CHARS) this
        else copy(content = content.substring(0, MAX_CONTENT_CHARS))

    companion object {
        const val MAX_CONTENT_CHARS = 2000
        private val REQUEST_TIMEOUT: JavaDuration = JavaDuration.ofSeconds(15)

        private fun defaultWebClient(): WebClient {
            val httpClient = HttpClient.create().responseTimeout(REQUEST_TIMEOUT)
            return WebClient.builder()
                .clientConnector(ReactorClientHttpConnector(httpClient))
                .build()
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class TavilyRequest(
    @get:JsonProperty("api_key") val apiKey: String,
    val query: String,
    @get:JsonProperty("max_results") val maxResults: Int,
    @get:JsonProperty("search_depth") val searchDepth: String = "basic",
    @get:JsonProperty("include_answer") val includeAnswer: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TavilyResponse(
    val query: String? = null,
    val results: List<TavilyResult> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TavilyResult(
    val title: String = "",
    val url: String = "",
    val content: String = "",
    val score: Double? = null,
)
