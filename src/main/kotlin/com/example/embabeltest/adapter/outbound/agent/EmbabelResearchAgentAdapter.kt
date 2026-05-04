package com.example.embabeltest.adapter.outbound.agent

import com.embabel.agent.api.invocation.AgentInvocation
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.example.embabeltest.domain.model.ResearchEvent
import com.example.embabeltest.domain.model.ResearchSummary
import com.example.embabeltest.domain.model.ResearchTopic
import com.example.embabeltest.domain.port.outbound.ResearchAgentPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import org.springframework.stereotype.Component
import reactor.core.publisher.Sinks

@Component
class EmbabelResearchAgentAdapter(
    private val agentPlatform: AgentPlatform,
) : ResearchAgentPort {

    override fun streamSummarize(topic: ResearchTopic): Flow<ResearchEvent> = channelFlow {
        val sink = Sinks.many().multicast().onBackpressureBuffer<ResearchEvent>(SINK_BUFFER, false)
        val listener = StreamingAgentEventListener(sink)

        send(ResearchEvent.Started(topic.value))

        val forwardJob = launch { sink.asFlux().asFlow().collect { send(it) } }

        val agentJob = launch(Dispatchers.IO) {
            val terminal: ResearchEvent = runCatching {
                AgentInvocation
                    .builder(agentPlatform)
                    .options(ProcessOptions().withListener(listener))
                    .build(AgentResearchSummary::class.java)
                    .invoke(UserInput(topic.value))
                    .toDomain(topic)
            }.fold(
                onSuccess = { ResearchEvent.Completed(it) },
                onFailure = { ResearchEvent.Failed(it.message ?: it::class.java.simpleName) },
            )
            send(terminal)
            sink.tryEmitComplete()
        }

        agentJob.join()
        forwardJob.cancel()
    }

    private fun AgentResearchSummary.toDomain(topic: ResearchTopic): ResearchSummary =
        ResearchSummary(
            topic = topic,
            headline = headline,
            keyPoints = keyPoints,
            conclusion = conclusion,
        )

    companion object {
        private const val SINK_BUFFER = 256
    }
}
