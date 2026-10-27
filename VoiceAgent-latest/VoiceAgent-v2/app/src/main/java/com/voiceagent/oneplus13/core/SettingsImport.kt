package com.voiceagent.oneplus13.core

import com.voiceagent.oneplus13.core.security.SecretsStore

/** Builds the complete write set before the only authoritative disk edit. */
internal fun prepareSettingsImport(data: ValidatedBackup, existing: List<MemoryFact>): Map<String, String?> = buildMap {
    putAll(data.secrets)
    if (data.facts.isNotEmpty()) put(SecretsStore.KEY_MEMORY_FACTS, encodeMemoryFacts(mergeImportedFacts(existing, data.facts)))
    data.formFactor?.let { put(SecretsStore.KEY_FORM_FACTOR, it) }
    data.overlayEnabled?.let { put(SecretsStore.KEY_OVERLAY_ENABLED, it.toString()) }
    data.overlayAlwaysVisible?.let { put(SecretsStore.KEY_OVERLAY_ALWAYS_VISIBLE, it.toString()) }
}
