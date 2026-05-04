package com.example.embabeltest.domain.model

data class DeepResearchReport(
    val topic: ResearchTopic,
    val executiveSummary: String,
    val sections: List<Section>,
    val sources: List<String>,
    val conclusion: String,
) {
    init {
        require(executiveSummary.isNotBlank()) { "executiveSummary must not be blank" }
        require(sections.isNotEmpty()) { "report must contain at least one section" }
        require(conclusion.isNotBlank()) { "conclusion must not be blank" }
    }
}
