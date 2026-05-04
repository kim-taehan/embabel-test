package com.example.embabeltest.domain.port.outbound

import com.example.embabeltest.domain.model.ResearchEvent
import com.example.embabeltest.domain.model.ResearchTopic
import kotlinx.coroutines.flow.Flow

fun interface ResearchAgentPort {
    fun streamSummarize(topic: ResearchTopic): Flow<ResearchEvent>
}
