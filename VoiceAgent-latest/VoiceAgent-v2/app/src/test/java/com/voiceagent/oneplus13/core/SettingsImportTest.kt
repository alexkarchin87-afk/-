package com.voiceagent.oneplus13.core

import com.voiceagent.oneplus13.core.security.SecretsStore
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SettingsImportTest {
    @Test fun combinedImportIncludesSecretsFactsAndUiInOneWriteSet() {
        val data = validateBackup(JSONObject("""{"format_version":2,"secrets":{"deepseek_api_key":"synthetic"},"memory_facts_v2":[{"text":"New fact","tier":"P3"}],"ui_preferences":{"form_factor_override":"PHONE","overlay_enabled":true,"overlay_always_visible":false}}"""))
        val existing = listOf(MemoryFact("Keep",MemoryTier.P1,1))
        val write = prepareSettingsImport(data,existing)
        assertEquals(5,write.size); assertEquals("synthetic",write[SecretsStore.KEY_DEEPSEEK_API_KEY])
        assertEquals("PHONE",write[SecretsStore.KEY_FORM_FACTOR]); assertEquals("true",write[SecretsStore.KEY_OVERLAY_ENABLED])
        assertEquals(2,JSONArray(write.getValue(SecretsStore.KEY_MEMORY_FACTS)).length())
        assertEquals(1,existing.size)
    }
    @Test fun omittedFieldsDoNotResetExistingSettings() {
        val data=validateBackup(JSONObject("""{"format_version":2,"secrets":{"deepseek_api_key":"synthetic"}}"""))
        assertEquals(setOf(SecretsStore.KEY_DEEPSEEK_API_KEY),prepareSettingsImport(data,emptyList()).keys)
    }
    @Test fun duplicateCannotDowngradePermanentFact() {
        val merged=mergeImportedFacts(listOf(MemoryFact("Fact",MemoryTier.P1,1)),listOf(" fact " to "P3")){2}
        assertEquals(1,merged.size); assertEquals(MemoryTier.P1,merged.single().tier)
    }
    @Test fun capacityDropsOnlyOldTemporaryFacts() {
        val existing=(1..6).map { MemoryFact("permanent$it " + "word ".repeat(39),MemoryTier.P1,it.toLong()) } + MemoryFact("temporary",MemoryTier.P3,0)
        val merged=mergeImportedFacts(existing,listOf("fresh" to "P2")){100}
        assertFalse(merged.any { it.text=="temporary" });assertEquals(6,merged.count{it.tier==MemoryTier.P1});assertTrue(merged.any{it.text=="fresh"})
    }
    @Test fun legacyImportPreservesExistingFactsAndEscapesUnicode() {
        val data=validateBackup(JSONObject("""{"format_version":1,"memory_facts":["Лекция: \"тест\"\nвторая строка"]}"""))
        val write=prepareSettingsImport(data,listOf(MemoryFact("Before",MemoryTier.P2,1)))
        val facts=JSONArray(write[SecretsStore.KEY_MEMORY_FACTS]);assertEquals(2,facts.length());assertEquals("P2",facts.getJSONObject(1).getString("tier"))
    }
}
