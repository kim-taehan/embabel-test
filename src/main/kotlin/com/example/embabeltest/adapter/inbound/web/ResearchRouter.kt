package com.example.embabeltest.adapter.inbound.web

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.coRouter

@Configuration
class ResearchRouter {

    @Bean
    fun researchRoutes(handler: ResearchHandler): RouterFunction<ServerResponse> = coRouter {
        contentType(MediaType.APPLICATION_JSON).nest {
            POST("/api/research/stream", handler::handleStream)
            POST("/api/deep-research/stream", handler::handleDeepResearchStream)
        }
    }
}
