package com.example.embabeltest.domain.model

data class ResearchSubtopic(
    val title: String,
    val rationale: String,
) {
    init {
        require(title.isNotBlank()) { "subtopic title must not be blank" }
        require(rationale.isNotBlank()) { "subtopic rationale must not be blank" }
    }
}

data class ResearchPlan(
    val mainTopic: ResearchTopic,
    val subtopics: List<ResearchSubtopic>,
) {
    init {
        require(subtopics.isNotEmpty()) { "plan must contain at least one subtopic" }
        require(subtopics.size <= MAX_SUBTOPICS) { "plan exceeds max=$MAX_SUBTOPICS subtopics" }
    }

    companion object {
        const val MAX_SUBTOPICS = 8
    }
}
