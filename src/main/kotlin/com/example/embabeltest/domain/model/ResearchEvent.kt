package com.example.embabeltest.domain.model

sealed interface ResearchEvent {
    val type: String

    data class Started(val topic: String) : ResearchEvent {
        override val type: String = TYPE
        companion object { const val TYPE = "started" }
    }

    data class ActionStarted(val action: String) : ResearchEvent {
        override val type: String = TYPE
        companion object { const val TYPE = "action-started" }
    }

    data class ActionCompleted(val action: String, val status: String, val durationMs: Long) : ResearchEvent {
        override val type: String = TYPE
        companion object { const val TYPE = "action-completed" }
    }

    data class ToolInvoked(val tool: String, val input: String) : ResearchEvent {
        override val type: String = TYPE
        companion object { const val TYPE = "tool-invoked" }
    }

    data class ToolReturned(val tool: String, val durationMs: Long, val resultPreview: String) : ResearchEvent {
        override val type: String = TYPE
        companion object { const val TYPE = "tool-returned" }
    }

    data class PlanFormulated(val subtopics: List<String>) : ResearchEvent {
        override val type: String = TYPE
        companion object { const val TYPE = "plan-formulated" }
    }

    data class SectionDrafted(val title: String, val sourceCount: Int) : ResearchEvent {
        override val type: String = TYPE
        companion object { const val TYPE = "section-drafted" }
    }

    data class Completed(val summary: ResearchSummary) : ResearchEvent {
        override val type: String = TYPE
        companion object { const val TYPE = "completed" }
    }

    data class DeepResearchCompleted(val report: DeepResearchReport) : ResearchEvent {
        override val type: String = TYPE
        companion object { const val TYPE = "deep-research-completed" }
    }

    data class Failed(val message: String) : ResearchEvent {
        override val type: String = TYPE
        companion object { const val TYPE = "failed" }
    }
}
