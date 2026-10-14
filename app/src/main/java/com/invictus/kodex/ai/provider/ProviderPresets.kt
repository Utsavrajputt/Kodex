package com.invictus.kodex.ai.provider

/**
 * Ready-made OpenAI-compatible hosts. [fallback] is only what the picker shows offline or when the host's
 * model listing fails; with a valid key the live list replaces it. Adding a host = one entry here.
 */
data class ProviderPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val keyUrl: String? = null,
    val fallback: List<AiModel> = emptyList(),
) {
    fun toProvider(): AiProvider = OpenAiProvider(
        id = id,
        displayName = name,
        baseUrl = baseUrl,
        defaultModels = fallback,
        tokenParam = "max_tokens",
    )
}

object ProviderPresets {
    private const val CTX = OpenAiProvider.DEFAULT_CONTEXT_CHARS

    val all: List<ProviderPreset> = listOf(
        ProviderPreset(
            "openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys",
            listOf(AiModel("openrouter/auto", "Auto Router", CTX)),
        ),
        ProviderPreset(
            "groq", "Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys",
            listOf(AiModel("llama-3.3-70b-versatile", "Llama 3.3 70B", CTX), AiModel("openai/gpt-oss-120b", "GPT-OSS 120B", CTX)),
        ),
        ProviderPreset(
            "deepseek", "DeepSeek", "https://api.deepseek.com/v1", "https://platform.deepseek.com/api_keys",
            listOf(AiModel("deepseek-chat", "DeepSeek Chat", CTX), AiModel("deepseek-reasoner", "DeepSeek Reasoner", CTX)),
        ),
        ProviderPreset(
            "mistral", "Mistral", "https://api.mistral.ai/v1", "https://console.mistral.ai/api-keys",
            listOf(AiModel("mistral-large-latest", "Mistral Large", CTX), AiModel("codestral-latest", "Codestral", CTX)),
        ),
        ProviderPreset(
            "xai", "xAI Grok", "https://api.x.ai/v1", "https://console.x.ai",
            listOf(AiModel("grok-4", "Grok 4", CTX), AiModel("grok-code-fast-1", "Grok Code Fast", CTX)),
        ),
        ProviderPreset(
            "together", "Together AI", "https://api.together.xyz/v1", "https://api.together.ai/settings/api-keys",
            listOf(
                AiModel("meta-llama/Llama-3.3-70B-Instruct-Turbo", "Llama 3.3 70B Turbo", CTX),
                AiModel("Qwen/Qwen2.5-Coder-32B-Instruct", "Qwen2.5 Coder 32B", CTX),
            ),
        ),
        ProviderPreset(
            "huggingface", "Hugging Face", "https://router.huggingface.co/v1", "https://huggingface.co/settings/tokens",
            listOf(
                AiModel("Qwen/Qwen3-Coder-480B-A35B-Instruct", "Qwen3 Coder 480B", CTX),
                AiModel("meta-llama/Llama-3.3-70B-Instruct", "Llama 3.3 70B", CTX),
            ),
        ),
        // Local server (e.g. Ollama in Termux). No key needed; the key field takes any text.
        ProviderPreset("ollama", "Ollama (local)", "http://localhost:11434/v1", null, emptyList()),
    )
}
