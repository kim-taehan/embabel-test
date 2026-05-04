package com.example.embabeltest.domain.model

@JvmInline
value class ResearchTopic(val value: String) {
    init {
        require(value.isNotBlank()) { "research topic must not be blank" }
        require(value.length <= MAX_LENGTH) { "research topic exceeds max length=$MAX_LENGTH" }
    }

    companion object {
        const val MAX_LENGTH = 500
    }
}
