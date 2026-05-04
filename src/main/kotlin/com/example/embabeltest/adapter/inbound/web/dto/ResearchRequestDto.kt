package com.example.embabeltest.adapter.inbound.web.dto

import com.example.embabeltest.domain.model.ResearchTopic

data class ResearchRequestDto(val topic: String) {
    fun toDomain(): ResearchTopic = ResearchTopic(topic)
}
