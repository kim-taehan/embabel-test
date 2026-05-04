package com.example.embabeltest.adapter.outbound.agent

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.unit.FakeOperationContext
import com.embabel.agent.test.unit.FakePromptRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class ResearchPlannerAgentTest {

    private val tools = ResearchTools(
        tavilyApiKey = "tvly-test",
        tavilyBaseUrl = "https://api.tavily.test",
        webClient = WebClient.builder().build(),
    )
    private val agent = ResearchPlannerAgent(researchTools = tools, angleCount = 4, keyPointCount = 5)

    @Test
    fun `planAngles prompts the LLM with the topic and angle count`() {
        val context = fakeContextReturning(ResearchOutline("CQRS", listOf("a", "b", "c", "d")))

        agent.planAngles(UserInput("CQRS"), context)

        val prompt = (context.promptRunner() as FakePromptRunner)
            .llmInvocations.first().messages.single().content
        assertTrue(prompt.contains("CQRS"), "prompt should mention the topic")
        assertTrue(prompt.contains("4"), "prompt should include angle count")
    }

    @Test
    fun `summarize prompt advertises tavily_search and current_date and mandates citation`() {
        val outline = ResearchOutline("CQRS", listOf("read model", "write model"))
        val context = fakeContextReturning(
            AgentResearchSummary(
                topic = "CQRS",
                headline = "h",
                keyPoints = listOf("k1", "k2", "k3", "k4", "k5"),
                conclusion = "c",
            ),
        )

        val result = agent.summarize(outline, context)

        assertEquals("CQRS", result.topic)
        val prompt = (context.promptRunner() as FakePromptRunner)
            .llmInvocations.first().messages.single().content
        assertTrue(prompt.contains("read model"))
        assertTrue(prompt.contains("write model"))
        assertTrue(prompt.contains("current_date"))
        assertTrue(prompt.contains("tavily_search"))
        assertTrue(prompt.contains("MUST"), "prompt should mandate grounded keyPoints")
        assertTrue(prompt.contains("[URL]") || prompt.contains("[https://"), "prompt should require URL citation in keyPoints")
    }

    private fun fakeContextReturning(response: Any): OperationContext {
        val context = FakeOperationContext.create()
        context.expectResponse(response)
        return context
    }
}
