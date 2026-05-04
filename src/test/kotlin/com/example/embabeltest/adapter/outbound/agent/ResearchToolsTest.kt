package com.example.embabeltest.adapter.outbound.agent

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ResearchToolsTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var tools: ResearchTools

    @BeforeEach
    fun setUp() {
        mockServer = MockWebServer().apply { start() }
        tools = ResearchTools(
            tavilyApiKey = "tvly-test",
            tavilyBaseUrl = mockServer.url("/").toString().trimEnd('/'),
            clock = Clock.fixed(Instant.parse("2026-05-04T00:00:00Z"), ZoneOffset.UTC),
            webClient = WebClient.builder().build(),
        )
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `currentDate returns ISO date based on injected clock`() {
        assertEquals("2026-05-04", tools.currentDate())
    }

    @Test
    fun `tavilySearch parses results and forwards api key`() {
        mockServer.enqueue(
            MockResponse()
                .setBody(
                    """
                    {
                      "query": "service mesh",
                      "results": [
                        {"title":"Service mesh - Wikipedia","url":"https://en.wikipedia.org/wiki/Service_mesh","content":"A service mesh is...","score":0.93},
                        {"title":"Istio docs","url":"https://istio.io/latest/docs/","content":"Istio extends...","score":0.81}
                      ]
                    }
                    """.trimIndent(),
                )
                .addHeader("Content-Type", "application/json"),
        )

        val results = tools.tavilySearch(query = "service mesh", maxResults = 5)

        assertEquals(2, results.size)
        assertEquals("https://en.wikipedia.org/wiki/Service_mesh", results[0].url)
        assertEquals("Istio docs", results[1].title)

        val recorded = mockServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/search", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"api_key\":\"tvly-test\""))
        assertTrue(body.contains("\"query\":\"service mesh\""))
        assertTrue(body.contains("\"max_results\":5"))
    }

    @Test
    fun `tavilySearch truncates oversized content per result`() {
        val long = "x".repeat(ResearchTools.MAX_CONTENT_CHARS + 500)
        mockServer.enqueue(
            MockResponse()
                .setBody(
                    """{"results":[{"title":"t","url":"https://x.test","content":"$long"}]}""",
                )
                .addHeader("Content-Type", "application/json"),
        )

        val results = tools.tavilySearch("anything")

        assertEquals(ResearchTools.MAX_CONTENT_CHARS, results.single().content.length)
    }

    @Test
    fun `tavilySearch fails fast when api key not configured`() {
        val unconfigured = ResearchTools(
            tavilyApiKey = "",
            tavilyBaseUrl = mockServer.url("/").toString(),
            clock = Clock.systemDefaultZone(),
            webClient = WebClient.builder().build(),
        )
        val ex = assertThrows(IllegalStateException::class.java) {
            unconfigured.tavilySearch("x")
        }
        assertTrue(ex.message!!.contains("TAVILY_API_KEY"))
    }
}
