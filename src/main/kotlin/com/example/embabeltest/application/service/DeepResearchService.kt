package com.example.embabeltest.application.service

import com.example.embabeltest.domain.model.ResearchEvent
import com.example.embabeltest.domain.model.ResearchTopic
import com.example.embabeltest.domain.port.inbound.DeepResearchStreamUseCase
import com.example.embabeltest.domain.port.outbound.DeepResearchAgentPort
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Service

@Service
class DeepResearchService(
    private val deepResearchAgentPort: DeepResearchAgentPort,
) : DeepResearchStreamUseCase {

    override fun stream(topic: ResearchTopic): Flow<ResearchEvent> =
        deepResearchAgentPort.streamDeepResearch(topic)
}
