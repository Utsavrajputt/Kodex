package com.invictus.kodex.ai.provider

/** Adding a provider = one AiProvider class + one entry here (+ settings strings). */
object ProviderRegistry {
    val all: List<AiProvider> = listOf<AiProvider>(ClaudeProvider(), OpenAiProvider(), GeminiProvider()).map { UsageTrackedProvider(it) }

    fun byId(id: String): AiProvider = all.firstOrNull { it.id == id } ?: all.first()
}
