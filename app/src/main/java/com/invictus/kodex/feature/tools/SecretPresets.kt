package com.invictus.kodex.feature.tools

import androidx.annotation.StringRes
import com.invictus.kodex.R

/** One secret of a preset: the GitHub secret name it is saved under. */
data class PresetField(
    val name: String,
    /** Long text (a JSON file): shown as a tall box with a "load from file" button. */
    val multiline: Boolean = false,
)

/** A group of secrets people commonly add next to the keystore; picked with a chip. */
data class SecretPreset(val id: String, @StringRes val title: Int, val fields: List<PresetField>)

/** Chip order after "Keystore" (and before "Custom"). Names follow what most Android workflows already read. */
val SECRET_PRESETS: List<SecretPreset> = listOf(
    SecretPreset("play", R.string.preset_play, listOf(PresetField("PLAY_SERVICE_ACCOUNT_JSON", multiline = true))),
    SecretPreset("google_services", R.string.preset_google_services, listOf(PresetField("GOOGLE_SERVICES_JSON", multiline = true))),
    SecretPreset("firebase", R.string.preset_firebase, listOf(PresetField("FIREBASE_APP_ID"), PresetField("FIREBASE_TOKEN"))),
    SecretPreset("maps", R.string.preset_maps, listOf(PresetField("MAPS_API_KEY"))),
    SecretPreset("pat", R.string.preset_pat, listOf(PresetField("PAT_TOKEN"))),
    SecretPreset("telegram", R.string.preset_telegram, listOf(PresetField("TELEGRAM_BOT_TOKEN"), PresetField("TELEGRAM_CHAT_ID"))),
    SecretPreset("sentry", R.string.preset_sentry, listOf(PresetField("SENTRY_AUTH_TOKEN"))),
)
