package com.example.embabeltest.application.service

import com.example.embabeltest.domain.model.ResearchEvent
import com.example.embabeltest.domain.model.ResearchTopic
import com.example.embabeltest.domain.port.inbound.ResearchStreamUseCase
import com.example.embabeltest.domain.port.outbound.ResearchAgentPort
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Service

@Service
class ResearchService(
    private val researchAgentPort: ResearchAgentPort,
) : ResearchStreamUseCase {

    override fun stream(topic: ResearchTopic): Flow<ResearchEvent> =
        researchAgentPort.streamSummarize(topic)
}
