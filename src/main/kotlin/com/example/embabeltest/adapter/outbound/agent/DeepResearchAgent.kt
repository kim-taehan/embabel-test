package com.example.embabeltest.adapter.outbound.agent

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.create
import com.embabel.agent.domain.io.UserInput
import com.embabel.common.ai.model.LlmOptions
import org.springframework.beans.factory.annotation.Value

data class AgentResearchSubtopic(
    val title: String,
    val rationale: String,
)

data class AgentResearchPlan(
    val mainTopic: String,
    val subtopics: List<AgentResearchSubtopic>,
)

data class AgentSection(
    val title: String,
    val body: String,
    val sources: List<String>,
)

data class AgentSectionDrafts(
    val sections: List<AgentSection>,
)

data class AgentReportFraming(
    val executiveSummary: String,
    val conclusion: String,
)

data class AgentDeepResearchReport(
    val topic: String,
    val executiveSummary: String,
    val sections: List<AgentSection>,
    val sources: List<String>,
    val conclusion: String,
)

@Agent(description = "Produce a long-form, source-grounded research report on an arbitrary topic")
class DeepResearchAgent(
    private val researchTools: ResearchTools,
    @param:Value("\${deep-research.subtopic-count:5}") private val subtopicCount: Int,
    @param:Value("\${deep-research.section-word-count:300}") private val sectionWordCount: Int,
) {

    @Action
    fun planSubtopics(userInput: UserInput, context: OperationContext): AgentResearchPlan =
        context.ai()
            .withLlm(LlmOptions.withAutoLlm().withTemperature(0.3))
            .create(
                """
                Decompose the user's topic into $subtopicCount distinct subtopics suitable for
                a thorough research report. Each subtopic must be:
                - substantive enough to fill a 200-400 word section,
                - non-overlapping with the others,
                - investigatable through public web sources.

                Return AgentResearchPlan with mainTopic (echo the user input) and subtopics.

                # User input
                ${userInput.content}
                """.trimIndent(),
            )

    @Action
    fun gatherSections(plan: AgentResearchPlan, context: OperationContext): AgentSectionDrafts {
        val drafts = plan.subtopics.map { subtopic ->
            context.ai()
                .withLlm(LlmOptions.withAutoLlm().withTemperature(0.2))
                .withToolObject(researchTools)
                .create<AgentSection>(
                    """
                    Draft ONE section of a report on '${plan.mainTopic}'. Subtopic: '${subtopic.title}'.

                    Steps:
                    1. Issue 1-2 tavily_search calls (no more) with focused queries.
                    2. Write a $sectionWordCount-word section grounded on the results.
                    3. Cite each claim inline as [https://url].

                    Return AgentSection { title, body (with inline [URL] citations), sources (deduped URL list) }.
                    Keep prose tight. No headings. No filler.
                    """.trimIndent(),
                )
        }
        return AgentSectionDrafts(drafts)
    }

    @Action
    fun frameReport(
        plan: AgentResearchPlan,
        drafts: AgentSectionDrafts,
        context: OperationContext,
    ): AgentReportFraming {
        val titlesBlock = drafts.sections.joinToString("\n") { section ->
            "- ${section.title}: ${section.body.take(160).replace('\n', ' ')}..."
        }
        return context.ai()
            .withLlm(LlmOptions.withAutoLlm().withTemperature(0.2))
            .withToolObject(researchTools)
            .create(
                """
                You are framing a research report on '${plan.mainTopic}'.
                The report already has ${drafts.sections.size} drafted sections (do NOT rewrite them).

                # Section briefs
                $titlesBlock

                Tasks:
                1. Call current_date() once.
                2. Write a 2-3 sentence executiveSummary that frames the topic and includes 'as of YYYY-MM-DD'.
                3. Write a 3-4 sentence conclusion that synthesizes the main findings across the sections.

                Return AgentReportFraming with executiveSummary and conclusion. Keep both concise.
                """.trimIndent(),
            )
    }

    @AchievesGoal(description = "A multi-section research report has been produced")
    @Action
    fun assembleReport(
        plan: AgentResearchPlan,
        drafts: AgentSectionDrafts,
        framing: AgentReportFraming,
    ): AgentDeepResearchReport = AgentDeepResearchReport(
        topic = plan.mainTopic,
        executiveSummary = framing.executiveSummary,
        sections = drafts.sections,
        sources = drafts.sections.flatMap { it.sources }.distinct(),
        conclusion = framing.conclusion,
    )
}
