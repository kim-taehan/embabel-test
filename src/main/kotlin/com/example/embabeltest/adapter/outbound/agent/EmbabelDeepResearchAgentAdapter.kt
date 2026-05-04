package com.example.embabeltest.adapter.outbound.agent

import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.ObjectAddedEvent
import com.embabel.agent.api.invocation.AgentInvocation
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.example.embabeltest.domain.model.DeepResearchReport
import com.example.embabeltest.domain.model.ResearchEvent
import com.example.embabeltest.domain.model.ResearchPlan
import com.example.embabeltest.domain.model.ResearchSubtopic
import com.example.embabeltest.domain.model.ResearchTopic
import com.example.embabeltest.domain.model.Section
import com.example.embabeltest.domain.port.outbound.DeepResearchAgentPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import org.springframework.stereotype.Component
import reactor.core.publisher.Sinks

@Component
class EmbabelDeepResearchAgentAdapter(
    private val agentPlatform: AgentPlatform,
) : DeepResearchAgentPort {

    override fun streamDeepResearch(topic: ResearchTopic): Flow<ResearchEvent> = channelFlow {
        val sink = Sinks.many().multicast().onBackpressureBuffer<ResearchEvent>(SINK_BUFFER, false)
        val baseListener = StreamingAgentEventListener(sink)
        val planListener = PlanAndSectionListener(sink)
        val combined = MulticastListener(listOf(baseListener, planListener))

        send(ResearchEvent.Started(topic.value))

        val forwardJob = launch { sink.asFlux().asFlow().collect { send(it) } }

        val agentJob = launch(Dispatchers.IO) {
            val terminal: ResearchEvent = runCatching {
                AgentInvocation
                    .builder(agentPlatform)
                    .options(ProcessOptions().withListener(combined))
                    .build(AgentDeepResearchReport::class.java)
                    .invoke(UserInput(topic.value))
                    .toDomain(topic)
            }.fold(
                onSuccess = { ResearchEvent.DeepResearchCompleted(it) },
                onFailure = { ResearchEvent.Failed(it.message ?: it::class.java.simpleName) },
            )
            send(terminal)
            sink.tryEmitComplete()
        }

        agentJob.join()
        forwardJob.cancel()
    }

    private fun AgentDeepResearchReport.toDomain(topic: ResearchTopic): DeepResearchReport =
        DeepResearchReport(
            topic = topic,
            executiveSummary = executiveSummary,
            sections = sections.map { it.toDomain() },
            sources = sources,
            conclusion = conclusion,
        )

    private fun AgentSection.toDomain(): Section =
        Section(title = title, body = body, sources = sources)

    private class MulticastListener(
        private val listeners: List<AgenticEventListener>,
    ) : AgenticEventListener {
        override fun onProcessEvent(event: AgentProcessEvent) {
            listeners.forEach { it.onProcessEvent(event) }
        }
    }

    private class PlanAndSectionListener(
        private val sink: Sinks.Many<ResearchEvent>,
    ) : AgenticEventListener {
        override fun onProcessEvent(event: AgentProcessEvent) {
            if (event !is ObjectAddedEvent) return
            when (val payload = event.value) {
                is AgentResearchPlan -> sink.tryEmitNext(
                    ResearchEvent.PlanFormulated(payload.subtopics.map { it.title }),
                )
                is AgentSection -> sink.tryEmitNext(
                    ResearchEvent.SectionDrafted(title = payload.title, sourceCount = payload.sources.size),
                )
            }
        }
    }

    @Suppress("unused")
    private fun ResearchPlan.echoForTypeReference(): ResearchSubtopic = subtopics.first()

    companion object {
        private const val SINK_BUFFER = 256
    }
}
