package com.invictus.kodex.ai.provider

import com.invictus.kodex.ai.prefs.AiPrefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiModelsTest {
    private fun entry(name: String, display: String, vararg methods: String, tokens: Long = 1_048_576) =
        """{"name":"models/$name","displayName":"$display","inputTokenLimit":$tokens,
            "supportedGenerationMethods":[${methods.joinToString(",") { "\"$it\"" }}]}"""

    private val page = JSONObject(
        """{"models":[
            ${entry("gemini-3.8-flash", "Gemini 3.8 Flash", "generateContent", "countTokens")},
            ${entry("gemini-3.1-pro-preview", "Gemini 3.1 Pro", "generateContent")},
            ${entry("gemini-3.8-flash-tts", "Gemini 3.8 Flash TTS", "generateContent")},
            ${entry("gemini-3-pro-image", "Nano Banana Pro", "generateContent")},
            ${entry("gemini-embedding-001", "Embedding", "embedContent")},
            ${entry("gemma-3-27b-it", "Gemma 3", "generateContent")},
            ${entry("gemini-2.5-flash", "Gemini 2.5 Flash", "generateContent", tokens = 10)}
        ]}""",
    )

    @Test fun parseKeepsOnlyChatGeminiModels() {
        val ids = GeminiProvider.parseModels(page).map { it.id }
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.1-pro-preview", "gemini-2.5-flash"), ids)
    }

    @Test fun parseUsesDisplayNameAndClampsContext() {
        val models = GeminiProvider.parseModels(page).associateBy { it.id }
        assertEquals("Gemini 3.8 Flash", models.getValue("gemini-3.8-flash").label)
        assertEquals(1_000_000, models.getValue("gemini-3.8-flash").maxContextChars)
        assertEquals(100_000, models.getValue("gemini-2.5-flash").maxContextChars)
    }

    @Test fun parseToleratesMissingModelsArray() {
        assertTrue(GeminiProvider.parseModels(JSONObject("{}")).isEmpty())
    }

    @Test fun mergePutsDefaultsFirstThenNewestOthers() {
        val defaults = GeminiProvider().defaultModels
        val fetched = listOf(
            AiModel("gemini-2.5-flash", "Gemini 2.5 Flash", 100_000),
            AiModel("gemini-3.7-flash", "Gemini 3.7 Flash", 1_000_000),
            AiModel("gemini-3.1-pro-preview", "From API", 1_000_000),
            AiModel("gemini-3.8-flash", "From API", 1_000_000),
        )
        val ids = ModelList.merge(defaults, fetched).map { it.id }
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.1-pro-preview", "gemini-3.7-flash", "gemini-2.5-flash"), ids)
        assertEquals("Gemini 3.8 Flash", ModelList.merge(defaults, fetched).first().label)
    }

    @Test fun mergeDropsDefaultsTheKeyCannotUse() {
        val defaults = GeminiProvider().defaultModels
        val fetched = listOf(AiModel("gemini-3.8-flash", "x", 1))
        assertEquals(listOf("gemini-3.8-flash"), ModelList.merge(defaults, fetched).map { it.id })
    }

    @Test fun mergeFallsBackToDefaultsWhenListingIsEmpty() {
        val defaults = GeminiProvider().defaultModels
        assertEquals(defaults, ModelList.merge(defaults, emptyList()))
    }

    @Test fun retiredGeminiModelsMigrateToCurrentFlash() {
        assertEquals("gemini-3.8-flash", AiPrefs.migrated("gemini-2.5-flash"))
        assertEquals("gemini-3.8-flash", AiPrefs.migrated("gemini-2.5-pro"))
        assertEquals("gemini-3.1-pro-preview", AiPrefs.migrated("gemini-3.1-pro-preview"))
        assertEquals("", AiPrefs.migrated(""))
    }
}
