package com.example.embabeltest.adapter.inbound.web.dto

import com.example.embabeltest.domain.model.ResearchSummary

data class ResearchResponseDto(
    val topic: String,
    val headline: String,
    val keyPoints: List<String>,
    val conclusion: String,
) {
    companion object {
        fun fromDomain(summary: ResearchSummary): ResearchResponseDto = ResearchResponseDto(
            topic = summary.topic.value,
            headline = summary.headline,
            keyPoints = summary.keyPoints,
            conclusion = summary.conclusion,
        )
    }
}
