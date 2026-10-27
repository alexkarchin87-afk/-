package com.voiceagent.oneplus13.core

import org.json.JSONArray
import org.json.JSONObject

internal fun mergeImportedFacts(
    existing: List<MemoryFact>, additions: List<Pair<String, String>>, now: () -> Long = System::currentTimeMillis
): List<MemoryFact> {
    val facts = existing.toMutableList()
    for ((text, tierName) in additions) {
        val trimmed = text.trim().take(200)
        if (trimmed.isBlank()) continue
        val tier = MemoryTier.valueOf(tierName)
        val previous = facts.filter { it.text.equals(trimmed, ignoreCase = true) }.maxByOrNull { it.tier.rank }
        facts.removeAll { it.text.equals(trimmed, ignoreCase = true) }
        facts += MemoryFact(trimmed, if (previous != null && previous.tier.rank > tier.rank) previous.tier else tier, now())
        fun words() = facts.sumOf { f -> f.text.split(Regex("\\s+")).count { it.isNotBlank() } }
        while (words() > 200) {
            val drop = facts.filter { it.tier == MemoryTier.P3 }.minByOrNull { it.timestampMillis } ?: break
            facts.remove(drop)
        }
    }
    return facts.toList()
}

internal fun encodeMemoryFacts(facts: List<MemoryFact>): String = JSONArray().apply {
    facts.forEach { put(JSONObject().put("text", it.text).put("tier", it.tier.name).put("ts", it.timestampMillis)) }
}.toString()
