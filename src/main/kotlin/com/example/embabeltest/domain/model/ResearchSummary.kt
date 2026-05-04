package com.example.embabeltest.domain.model

data class ResearchSummary(
    val topic: ResearchTopic,
    val headline: String,
    val keyPoints: List<String>,
    val conclusion: String,
) {
    init {
        require(headline.isNotBlank()) { "headline must not be blank" }
        require(keyPoints.isNotEmpty()) { "keyPoints must not be empty" }
        require(conclusion.isNotBlank()) { "conclusion must not be blank" }
    }
}
