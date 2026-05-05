package com.example.embabeltest.adapter.outbound.agent

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.unit.FakeOperationContext
import com.embabel.agent.test.unit.FakePromptRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class DeepResearchAgentTest {

    private val tools = ResearchTools(
        tavilyApiKey = "tvly-test",
        tavilyBaseUrl = "https://api.tavily.test",
        webClient = WebClient.builder().build(),
    )
    private val agent = DeepResearchAgent(researchTools = tools, subtopicCount = 5, sectionWordCount = 300)

    @Test
    fun `planSubtopics asks for the configured number of subtopics`() {
        val context = fakeContextReturning(
            AgentResearchPlan(
                mainTopic = "CQRS",
                subtopics = listOf(AgentResearchSubtopic("Definition", "core")),
            ),
        )

        agent.planSubtopics(UserInput("CQRS"), context)

        val prompt = (context.promptRunner() as FakePromptRunner)
            .llmInvocations.first().messages.single().content
        assertTrue(prompt.contains("CQRS"))
        assertTrue(prompt.contains("5"))
    }

    @Test
    fun `gatherSections drafts one section per subtopic`() {
        val plan = AgentResearchPlan(
            mainTopic = "CQRS",
            subtopics = listOf(
                AgentResearchSubtopic("Definition", "core"),
                AgentResearchSubtopic("Eventual consistency", "tradeoff"),
            ),
        )
        val context = FakeOperationContext.create()
        plan.subtopics.forEach { sub ->
            context.expectResponse(
                AgentSection(
                    title = sub.title,
                    body = "body for ${sub.title}",
                    sources = listOf("https://example.com/${sub.title.lowercase()}"),
                ),
            )
        }

        val drafts = agent.gatherSections(plan, context)

        assertEquals(2, drafts.sections.size)
    }

    @Test
    fun `gatherSections issues one LLM invocation per subtopic with subtopic-specific prompts`() {
        val plan = AgentResearchPlan(
            mainTopic = "CQRS",
            subtopics = listOf(
                AgentResearchSubtopic("Definition", "core"),
                AgentResearchSubtopic("Eventual consistency", "tradeoff"),
                AgentResearchSubtopic("Failure modes", "edge"),
            ),
        )
        val context = FakeOperationContext.create()
        plan.subtopics.forEach { sub ->
            context.expectResponse(
                AgentSection(sub.title, "body", listOf("https://example.com/${sub.title.lowercase()}")),
            )
        }

        agent.gatherSections(plan, context)

        val invocations = (context.promptRunner() as FakePromptRunner).llmInvocations
        assertEquals(3, invocations.size)
        plan.subtopics.forEachIndexed { idx, sub ->
            val prompt = invocations[idx].messages.single().content
            assertTrue(prompt.contains(sub.title), "invocation $idx should mention '${sub.title}'")
            assertTrue(prompt.contains("CQRS"), "invocation $idx should mention main topic")
            assertTrue(prompt.contains("tavily_search"), "invocation $idx should advertise tavily_search")
        }
    }

    @Test
    fun `frameReport prompt includes section briefs and current_date instruction`() {
        val plan = AgentResearchPlan(
            mainTopic = "CQRS",
            subtopics = listOf(AgentResearchSubtopic("Definition", "core")),
        )
        val drafts = AgentSectionDrafts(
            sections = listOf(AgentSection("Definition", "CQRS separates read and write models...", listOf("https://x.test"))),
        )
        val context = fakeContextReturning(AgentReportFraming("es", "c"))

        agent.frameReport(plan, drafts, context)

        val prompt = (context.promptRunner() as FakePromptRunner)
            .llmInvocations.first().messages.single().content
        assertTrue(prompt.contains("Definition"))
        assertTrue(prompt.contains("CQRS separates"))
        assertTrue(prompt.contains("current_date"))
    }

    @Test
    fun `assembleReport stitches drafts framing and deduplicates sources`() {
        val plan = AgentResearchPlan(
            mainTopic = "CQRS",
            subtopics = listOf(
                AgentResearchSubtopic("Definition", "core"),
                AgentResearchSubtopic("Tradeoffs", "balance"),
            ),
        )
        val drafts = AgentSectionDrafts(
            sections = listOf(
                AgentSection("Definition", "...", listOf("https://a.test", "https://b.test")),
                AgentSection("Tradeoffs", "...", listOf("https://b.test", "https://c.test")),
            ),
        )
        val framing = AgentReportFraming("executive", "conclusion")

        val report = agent.assembleReport(plan, drafts, framing)

        assertEquals("CQRS", report.topic)
        assertEquals("executive", report.executiveSummary)
        assertEquals("conclusion", report.conclusion)
        assertEquals(2, report.sections.size)
        assertEquals(listOf("https://a.test", "https://b.test", "https://c.test"), report.sources)
    }

    private fun fakeContextReturning(response: Any): OperationContext {
        val context = FakeOperationContext.create()
        context.expectResponse(response)
        return context
    }
}
