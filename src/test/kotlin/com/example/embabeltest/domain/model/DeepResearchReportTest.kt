package com.example.embabeltest.domain.model

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DeepResearchReportTest {

    @Test
    fun `requires at least one section`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeepResearchReport(
                topic = ResearchTopic("t"),
                executiveSummary = "es",
                sections = emptyList(),
                sources = emptyList(),
                conclusion = "c",
            )
        }
    }

    @Test
    fun `requires non-blank executiveSummary and conclusion`() {
        val section = Section("title", "body", listOf("https://x.test"))
        assertThrows(IllegalArgumentException::class.java) {
            DeepResearchReport(
                topic = ResearchTopic("t"),
                executiveSummary = "  ",
                sections = listOf(section),
                sources = emptyList(),
                conclusion = "c",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeepResearchReport(
                topic = ResearchTopic("t"),
                executiveSummary = "es",
                sections = listOf(section),
                sources = emptyList(),
                conclusion = "  ",
            )
        }
    }
}
