package com.example.embabeltest.adapter.inbound.web.dto

import com.example.embabeltest.domain.model.DeepResearchReport
import com.example.embabeltest.domain.model.Section

data class SectionDto(
    val title: String,
    val body: String,
    val sources: List<String>,
) {
    companion object {
        fun fromDomain(section: Section): SectionDto = SectionDto(
            title = section.title,
            body = section.body,
            sources = section.sources,
        )
    }
}

data class DeepResearchReportDto(
    val topic: String,
    val executiveSummary: String,
    val sections: List<SectionDto>,
    val sources: List<String>,
    val conclusion: String,
) {
    companion object {
        fun fromDomain(report: DeepResearchReport): DeepResearchReportDto = DeepResearchReportDto(
            topic = report.topic.value,
            executiveSummary = report.executiveSummary,
            sections = report.sections.map(SectionDto::fromDomain),
            sources = report.sources,
            conclusion = report.conclusion,
        )
    }
}
