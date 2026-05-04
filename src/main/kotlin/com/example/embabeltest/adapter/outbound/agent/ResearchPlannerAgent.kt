package com.example.embabeltest.adapter.outbound.agent

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.create
import com.embabel.agent.domain.io.UserInput
import com.embabel.common.ai.model.LlmOptions
import org.springframework.beans.factory.annotation.Value

data class ResearchOutline(
    val topic: String,
    val angles: List<String>,
)

data class AgentResearchSummary(
    val topic: String,
    val headline: String,
    val keyPoints: List<String>,
    val conclusion: String,
)

@Agent(description = "Produce a structured research summary for an arbitrary topic")
class ResearchPlannerAgent(
    private val researchTools: ResearchTools,
    @param:Value("\${research.angle-count:4}") private val angleCount: Int,
    @param:Value("\${research.key-point-count:5}") private val keyPointCount: Int,
) {

    @Action
    fun planAngles(userInput: UserInput, context: OperationContext): ResearchOutline =
        context.ai()
            .withLlm(LlmOptions.withAutoLlm().withTemperature(0.3))
            .create(
                """
                You are a research planner. Identify the topic in the user input and
                propose $angleCount distinct angles worth investigating.

                Return JSON matching ResearchOutline { topic: String, angles: List<String> }.

                # User input
                ${userInput.content}
                """.trimIndent(),
            )

    @AchievesGoal(description = "A structured research summary has been produced")
    @Action
    fun summarize(outline: ResearchOutline, context: OperationContext): AgentResearchSummary =
        context.ai()
            .withLlm(LlmOptions.withAutoLlm().withTemperature(0.2))
            .withToolObject(researchTools)
            .create(
                """
                Synthesize a concise research summary on '${outline.topic}'.
                Cover these angles: ${outline.angles.joinToString("; ")}.

                # Tool usage policy (mandatory)
                1. Call current_date() once and embed the result in the headline as 'as of YYYY-MM-DD'.
                2. Call tavily_search(query) at least once with a focused query about '${outline.topic}'.
                   Issue additional searches if a single result set is insufficient.
                3. Every bullet in keyPoints MUST be grounded on a tavily_search result and MUST cite
                   the result's URL inline in square brackets, e.g. '... [https://example.com/page]'.
                4. If tavily_search returns no usable results, do NOT fabricate facts: return fewer
                   keyPoints and state in the conclusion that sources were unavailable.

                Return JSON matching AgentResearchSummary with:
                - topic: '${outline.topic}'
                - headline: a single sentence headline (must include the 'as of' date)
                - keyPoints: up to $keyPointCount factual bullet points, each ending with a [URL] citation
                - conclusion: a closing 2-3 sentence takeaway
                """.trimIndent(),
            )
}
