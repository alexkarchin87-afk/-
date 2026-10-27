package com.voiceagent.oneplus13.core.security

import org.junit.Assert.*
import org.junit.Test

class SettingsCommitterTest {
    @Test fun allCategoriesUseOneCommit() {
        val state = mutableMapOf("secret" to "old", "memory" to "before")
        var commits = 0
        val store = SettingsCommitter(state::get) { values -> commits++; values.forEach { (k,v) -> if(v==null) state.remove(k) else state[k]=v }; true }
        store.putAll(mapOf("secret" to "new", "memory" to "after", "ui" to "PHONE"))
        assertEquals(1, commits)
        assertEquals(mapOf("secret" to "new", "memory" to "after", "ui" to "PHONE"), state)
    }
    @Test fun failedDiskCommitRestoresEvenAnAlreadyChangedMemoryCache() {
        val state = mutableMapOf("secret" to "old", "memory" to "before")
        val original = state.toMap(); var commits = 0
        val store = SettingsCommitter(state::get) { values ->
            values.forEach { (k,v) -> if(v==null) state.remove(k) else state[k]=v }
            ++commits > 1
        }
        assertThrows(IllegalStateException::class.java) { store.putAll(mapOf("secret" to "new", "ui" to "PHONE")) }
        assertEquals(original, state); assertEquals(2, commits)
    }
    @Test fun thrownWriteAlsoRestoresSnapshot() {
        val state = mutableMapOf("x" to "old"); var first = true
        val store = SettingsCommitter(state::get) { values ->
            if(first) { first=false; state["x"]="new"; throw java.io.IOException("synthetic") }
            values.forEach { (k,v) -> if(v==null) state.remove(k) else state[k]=v }; true
        }
        assertThrows(java.io.IOException::class.java) { store.putAll(mapOf("x" to "new")) }
        assertEquals("old",state["x"])
    }
    @Test fun failedRollbackIsNotReportedAsSuccess() {
        val store = SettingsCommitter({ "old" }) { false }
        val error = assertThrows(IllegalStateException::class.java) { store.putAll(mapOf("x" to "new")) }
        assertTrue(error.message!!.contains("rollback failed")); assertEquals(1,error.cause!!.suppressed.size)
    }
    @Test fun emptyImportDoesNotWrite() {
        SettingsCommitter({ error("No read expected") }) { error("No commit expected") }.putAll(emptyMap())
    }
    @Test fun migrationIsAtomicAndDoesNotOverwriteModernValuesOnReopen() {
        val state=mutableMapOf<String,String>();var commits=0
        val store=SettingsCommitter(state::get){values->commits++;values.forEach{(k,v)->if(v==null)state.remove(k) else state[k]=v};true}
        store.migrate("schema","1"){mapOf("memory" to "old")}
        assertEquals(mapOf("schema" to "1","memory" to "old"),state);assertEquals(1,commits)
        store.putAll(mapOf("memory" to "new"))
        store.migrate("schema","1"){error("must never reimport stale mirror")}
        assertEquals("new",state["memory"]);assertEquals(2,commits)
    }
    @Test fun failedMigrationRollsBackMarkerAndCanRetry() {
        val state=mutableMapOf<String,String>();var writes=0
        val store=SettingsCommitter(state::get){values->values.forEach{(k,v)->if(v==null)state.remove(k) else state[k]=v};++writes!=1}
        assertThrows(IllegalStateException::class.java){store.migrate("schema","1"){mapOf("memory" to "legacy")}}
        assertTrue(state.isEmpty())
        store.migrate("schema","1"){mapOf("memory" to "legacy")}
        assertEquals("legacy",state["memory"]);assertEquals("1",state["schema"])
    }
    @Test fun unknownSchemaIsNeverDowngraded() {
        val store=SettingsCommitter({"99"}){error("must not write")}
        assertThrows(IllegalStateException::class.java){store.migrate("schema","1"){emptyMap()}}
    }
}
