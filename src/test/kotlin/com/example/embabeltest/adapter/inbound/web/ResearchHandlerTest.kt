package com.example.embabeltest.adapter.inbound.web

import com.epages.restdocs.apispec.ResourceSnippetParameters
import com.epages.restdocs.apispec.WebTestClientRestDocumentationWrapper.document
import com.example.embabeltest.adapter.inbound.web.dto.ResearchEventDto
import com.example.embabeltest.adapter.inbound.web.dto.ResearchRequestDto
import com.example.embabeltest.domain.model.ResearchEvent
import com.example.embabeltest.domain.model.ResearchSummary
import com.example.embabeltest.domain.model.ResearchTopic
import com.example.embabeltest.domain.port.inbound.DeepResearchStreamUseCase
import com.example.embabeltest.domain.port.inbound.ResearchStreamUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.http.MediaType
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.RestDocumentationExtension
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.documentationConfiguration
import org.springframework.test.web.reactive.server.WebTestClient

@ExtendWith(RestDocumentationExtension::class)
class ResearchHandlerTest {

    private val researchStreamUseCase = mockk<ResearchStreamUseCase>()
    private val deepResearchStreamUseCase = mockk<DeepResearchStreamUseCase>()
    private val handler = ResearchHandler(researchStreamUseCase, deepResearchStreamUseCase)
    private lateinit var webTestClient: WebTestClient

    @BeforeEach
    fun setUp(restDocumentation: RestDocumentationContextProvider) {
        webTestClient = WebTestClient
            .bindToRouterFunction(ResearchRouter().researchRoutes(handler))
            .configureClient()
            .filter(documentationConfiguration(restDocumentation))
            .build()
    }

    @Test
    fun `POST api research stream emits SSE events terminated by completed`() {
        val topicValue = "service mesh"
        val topic = ResearchTopic(topicValue)
        val summary = ResearchSummary(
            topic = topic,
            headline = "h",
            keyPoints = listOf("k1"),
            conclusion = "c",
        )
        val events = listOf(
            ResearchEvent.Started(topicValue),
            ResearchEvent.ToolInvoked("current_date", ""),
            ResearchEvent.ToolReturned("current_date", 1, "2026-05-04"),
            ResearchEvent.Completed(summary),
        )
        every { researchStreamUseCase.stream(topic) } returns flowOf(*events.toTypedArray())

        webTestClient.post().uri("/api/research/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(ResearchRequestDto(topicValue))
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody()
            .consumeWith(
                document<ByteArray>(
                    identifier = "research-stream",
                    resourceDetails = ResourceSnippetParameters.builder()
                        .summary("Stream a research summary as Server-Sent Events")
                        .description(
                            "Returns text/event-stream. Events: started → tool-invoked / tool-returned → completed. " +
                                "Terminal 'completed' carries the research summary payload (topic, headline, keyPoints, conclusion).",
                        )
                        .tag("research"),
                    snippets = arrayOf(
                        requestFields(
                            fieldWithPath("topic")
                                .type(JsonFieldType.STRING)
                                .description("Topic to research (1..${ResearchTopic.MAX_LENGTH} chars)"),
                        ),
                    ),
                ),
            )

        val decoded = webTestClient.post().uri("/api/research/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(ResearchRequestDto(topicValue))
            .exchange()
            .returnResult(ResearchEventDto::class.java)
            .responseBody
            .collectList()
            .block()!!

        assertEquals(events.size, decoded.size)
        assertEquals(ResearchEvent.Started.TYPE, decoded[0].type)
        assertEquals(ResearchEvent.ToolInvoked.TYPE, decoded[1].type)
        assertEquals(ResearchEvent.ToolReturned.TYPE, decoded[2].type)
        assertEquals(ResearchEvent.Completed.TYPE, decoded.last().type)
        assertTrue(decoded.last().payload.containsKey("summary"))
    }

    @Test
    fun `POST api deep-research stream emits plan, sections, and final report`() {
        val topicValue = "service mesh"
        val topic = ResearchTopic(topicValue)
        val report = com.example.embabeltest.domain.model.DeepResearchReport(
            topic = topic,
            executiveSummary = "es",
            sections = listOf(
                com.example.embabeltest.domain.model.Section(
                    title = "Definition",
                    body = "...",
                    sources = listOf("https://example.com/a"),
                ),
            ),
            sources = listOf("https://example.com/a"),
            conclusion = "c",
        )
        val events = listOf(
            ResearchEvent.Started(topicValue),
            ResearchEvent.PlanFormulated(listOf("Definition", "History")),
            ResearchEvent.SectionDrafted("Definition", 1),
            ResearchEvent.DeepResearchCompleted(report),
        )
        every { deepResearchStreamUseCase.stream(topic) } returns flowOf(*events.toTypedArray())

        webTestClient.post().uri("/api/deep-research/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(ResearchRequestDto(topicValue))
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody()
            .consumeWith(
                document<ByteArray>(
                    identifier = "deep-research-stream",
                    resourceDetails = ResourceSnippetParameters.builder()
                        .summary("Stream a long-form research report as Server-Sent Events")
                        .description(
                            "Returns text/event-stream. The agent decomposes the topic into subtopics, " +
                                "researches each via tavily_search, then composes a multi-section report. " +
                                "Events fire as actions and tools execute; the terminal 'deep-research-completed' " +
                                "event carries the full DeepResearchReport payload (executiveSummary, sections, " +
                                "deduplicated sources, conclusion).",
                        )
                        .tag("research"),
                    snippets = arrayOf(
                        requestFields(
                            fieldWithPath("topic")
                                .type(JsonFieldType.STRING)
                                .description("Topic to research (1..${ResearchTopic.MAX_LENGTH} chars)"),
                        ),
                    ),
                ),
            )

        val decoded = webTestClient.post().uri("/api/deep-research/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(ResearchRequestDto(topicValue))
            .exchange()
            .returnResult(ResearchEventDto::class.java)
            .responseBody
            .collectList()
            .block()!!

        assertEquals(events.size, decoded.size)
        assertEquals(ResearchEvent.Started.TYPE, decoded[0].type)
        assertEquals(ResearchEvent.PlanFormulated.TYPE, decoded[1].type)
        assertEquals(ResearchEvent.SectionDrafted.TYPE, decoded[2].type)
        assertEquals(ResearchEvent.DeepResearchCompleted.TYPE, decoded.last().type)
        assertTrue(decoded.last().payload.containsKey("report"))
    }
}
