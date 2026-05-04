package com.example.embabeltest.adapter.inbound.web.dto

import com.example.embabeltest.domain.model.ResearchEvent

data class ResearchEventDto(
    val type: String,
    val payload: Map<String, Any?>,
) {
    companion object {
        fun fromDomain(event: ResearchEvent): ResearchEventDto = when (event) {
            is ResearchEvent.Started -> ResearchEventDto(event.type, mapOf("topic" to event.topic))
            is ResearchEvent.ActionStarted -> ResearchEventDto(event.type, mapOf("action" to event.action))
            is ResearchEvent.ActionCompleted -> ResearchEventDto(
                event.type,
                mapOf("action" to event.action, "status" to event.status, "durationMs" to event.durationMs),
            )
            is ResearchEvent.ToolInvoked -> ResearchEventDto(
                event.type,
                mapOf("tool" to event.tool, "input" to event.input),
            )
            is ResearchEvent.ToolReturned -> ResearchEventDto(
                event.type,
                mapOf("tool" to event.tool, "durationMs" to event.durationMs, "preview" to event.resultPreview),
            )
            is ResearchEvent.PlanFormulated -> ResearchEventDto(
                event.type,
                mapOf("subtopics" to event.subtopics),
            )
            is ResearchEvent.SectionDrafted -> ResearchEventDto(
                event.type,
                mapOf("title" to event.title, "sourceCount" to event.sourceCount),
            )
            is ResearchEvent.Completed -> ResearchEventDto(
                event.type,
                mapOf("summary" to ResearchResponseDto.fromDomain(event.summary)),
            )
            is ResearchEvent.DeepResearchCompleted -> ResearchEventDto(
                event.type,
                mapOf("report" to DeepResearchReportDto.fromDomain(event.report)),
            )
            is ResearchEvent.Failed -> ResearchEventDto(event.type, mapOf("message" to event.message))
        }
    }
}
