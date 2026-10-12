package com.invictus.kodex.ai.provider

import kotlinx.coroutines.flow.Flow

interface AiProvider {
    val id: String            // "claude" | "openai" | "gemini"
    val displayName: String
    val defaultModels: List<AiModel>

    fun capabilities(): Capabilities

    /**
     * Live model catalogue for this key, or null when the provider can't list models / the call failed
     * (callers then fall back to [defaultModels]). Never throws for HTTP or network problems.
     */
    suspend fun listModels(key: String): List<AiModel>? = null

    /** One-shot call; throws [AiException] on failure. */
    suspend fun complete(key: String, model: String, request: AiRequest): AiResponse

    /** Streaming call; emits Delta* then Done, or a single Error. Collector cancellation cancels the HTTP call. */
    fun stream(key: String, model: String, request: AiRequest): Flow<AiEvent>
}
