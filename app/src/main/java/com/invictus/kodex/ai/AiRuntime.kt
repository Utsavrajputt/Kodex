package com.invictus.kodex.ai

import android.app.Application
import com.invictus.kodex.ai.keys.AiKeyStore
import com.invictus.kodex.ai.prefs.AiPrefs
import com.invictus.kodex.ai.provider.AiError
import com.invictus.kodex.ai.provider.AiException
import com.invictus.kodex.ai.provider.AiProvider
import com.invictus.kodex.ai.provider.ProviderRegistry
import kotlinx.coroutines.flow.first

/** Shared provider/key/model resolution for the M20 entry-point composables. */
class AiRuntime(app: Application) {
    private val prefs = AiPrefs(app)
    private val keyStore = AiKeyStore(app)

    /** provider, model, key — throws [AiException](NoKey) when the vault has no key. */
    suspend fun resolve(): Triple<AiProvider, String, String> {
        if (!prefs.consentGranted.first()) throw AiException(AiError.NoConsent())
        val provider = ProviderRegistry.byId(prefs.providerId.first())
        val model = prefs.currentModel(provider.id, provider.defaultModels)
        val key = keyStore.get(provider.id)
        if (key.isNullOrBlank()) throw AiException(AiError.NoKey())
        return Triple(provider, model, key)
    }

    suspend fun globs(): String = prefs.excludeGlobs.first()
}
