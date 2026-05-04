package com.example.embabeltest.domain.port.outbound

import com.example.embabeltest.domain.model.ResearchEvent
import com.example.embabeltest.domain.model.ResearchTopic
import kotlinx.coroutines.flow.Flow

fun interface DeepResearchAgentPort {
    fun streamDeepResearch(topic: ResearchTopic): Flow<ResearchEvent>
}
