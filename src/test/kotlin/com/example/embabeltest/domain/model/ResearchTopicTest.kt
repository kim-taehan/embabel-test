package com.example.embabeltest.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ResearchTopicTest {

    @Test
    fun `accepts non-blank value within max length`() {
        val topic = ResearchTopic("hexagonal architecture")
        assertEquals("hexagonal architecture", topic.value)
    }

    @Test
    fun `rejects blank value`() {
        assertThrows(IllegalArgumentException::class.java) { ResearchTopic("   ") }
    }

    @Test
    fun `rejects value exceeding max length`() {
        val tooLong = "x".repeat(ResearchTopic.MAX_LENGTH + 1)
        assertThrows(IllegalArgumentException::class.java) { ResearchTopic(tooLong) }
    }
}
