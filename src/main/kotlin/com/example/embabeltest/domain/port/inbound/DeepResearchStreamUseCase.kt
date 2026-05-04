package com.example.embabeltest.domain.port.inbound

import com.example.embabeltest.domain.model.ResearchEvent
import com.example.embabeltest.domain.model.ResearchTopic
import kotlinx.coroutines.flow.Flow

fun interface DeepResearchStreamUseCase {
    fun stream(topic: ResearchTopic): Flow<ResearchEvent>
}
