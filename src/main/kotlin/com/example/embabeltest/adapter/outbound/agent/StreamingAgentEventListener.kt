package com.example.embabeltest.adapter.outbound.agent

import com.embabel.agent.api.event.ActionExecutionResultEvent
import com.embabel.agent.api.event.ActionExecutionStartEvent
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.ToolCallRequestEvent
import com.embabel.agent.api.event.ToolCallResponseEvent
import com.example.embabeltest.domain.model.ResearchEvent
import reactor.core.publisher.Sinks

class StreamingAgentEventListener(
    private val sink: Sinks.Many<ResearchEvent>,
) : AgenticEventListener {

    override fun onProcessEvent(event: AgentProcessEvent) {
        val mapped = when (event) {
            is ActionExecutionStartEvent -> ResearchEvent.ActionStarted(
                action = event.action.shortName(),
            )
            is ActionExecutionResultEvent -> ResearchEvent.ActionCompleted(
                action = event.action.shortName(),
                status = event.actionStatus.status.name,
                durationMs = event.runningTime.toMillis(),
            )
            is ToolCallRequestEvent -> ResearchEvent.ToolInvoked(
                tool = event.tool,
                input = event.toolInput.takeUnless(String::isBlank).orEmpty(),
            )
            is ToolCallResponseEvent -> ResearchEvent.ToolReturned(
                tool = event.request.tool,
                durationMs = event.runningTime.toMillis(),
                resultPreview = event.resultPreview(),
            )
            else -> null
        }
        if (mapped != null) sink.tryEmitNext(mapped)
    }

    private fun ToolCallResponseEvent.resultPreview(): String {
        val raw = runCatching { result.toString() }.getOrDefault("")
        return if (raw.length <= MAX_PREVIEW_CHARS) raw else raw.substring(0, MAX_PREVIEW_CHARS) + "..."
    }

    companion object {
        private const val MAX_PREVIEW_CHARS = 240
    }
}
