package com.example.embabeltest.adapter.inbound.web

import com.example.embabeltest.adapter.inbound.web.dto.ResearchEventDto
import com.example.embabeltest.adapter.inbound.web.dto.ResearchRequestDto
import com.example.embabeltest.domain.model.ResearchEvent
import com.example.embabeltest.domain.model.ResearchTopic
import com.example.embabeltest.domain.port.inbound.DeepResearchStreamUseCase
import com.example.embabeltest.domain.port.inbound.ResearchStreamUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.bodyAndAwait

@Component
class ResearchHandler(
    private val researchStreamUseCase: ResearchStreamUseCase,
    private val deepResearchStreamUseCase: DeepResearchStreamUseCase,
) {

    suspend fun handleStream(request: ServerRequest): ServerResponse =
        streamingResponse(request, researchStreamUseCase::stream)

    suspend fun handleDeepResearchStream(request: ServerRequest): ServerResponse =
        streamingResponse(request, deepResearchStreamUseCase::stream)

    private suspend fun streamingResponse(
        request: ServerRequest,
        streamFactory: (ResearchTopic) -> Flow<ResearchEvent>,
    ): ServerResponse {
        val dto = request.awaitBody<ResearchRequestDto>()
        val events = streamFactory(dto.toDomain()).map { event ->
            val payload = ResearchEventDto.fromDomain(event)
            ServerSentEvent.builder(payload).event(payload.type).build()
        }
        return ServerResponse.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .bodyAndAwait(events)
    }
}
