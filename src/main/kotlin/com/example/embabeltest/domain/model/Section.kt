package com.example.embabeltest.domain.model

data class Section(
    val title: String,
    val body: String,
    val sources: List<String>,
) {
    init {
        require(title.isNotBlank()) { "section title must not be blank" }
        require(body.isNotBlank()) { "section body must not be blank" }
    }
}
