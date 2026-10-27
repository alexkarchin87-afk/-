package com.voiceagent.oneplus13.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoskModelFilesTest {
    @get:Rule val folder = TemporaryFolder()
    private fun files(vararg names: String) { names.forEach { File(folder.root,it).apply { parentFile?.mkdirs(); writeText("fixture") } } }
    @Test fun uuidAloneIsNotAModel() { files("uuid"); assertTrue(VoskModelFiles.missing(folder.root).isNotEmpty()) }
    @Test fun acceptsSupportedGraphLayoutsButNotOneHalfOfSplitGraph() {
        files("uuid","am/final.mdl","conf/mfcc.conf","graph/HCLr.fst")
        assertEquals(listOf("graph/FST"),VoskModelFiles.missing(folder.root))
        files("graph/Gr.fst"); assertTrue(VoskModelFiles.missing(folder.root).isEmpty())
        File(folder.root,"graph/Gr.fst").delete(); files("graph/HCLG.fst")
        assertTrue(VoskModelFiles.missing(folder.root).isEmpty())
    }
    @Test fun incompleteStampedCacheForcesRecopyWithoutDeletingUnrelatedFiles() {
        files("uuid","keep.txt"); assertTrue(VoskModelFiles.invalidateIncompleteCache(folder.root))
        assertFalse(File(folder.root,"uuid").exists()); assertTrue(File(folder.root,"keep.txt").exists())
        assertFalse(VoskModelFiles.invalidateIncompleteCache(folder.root))
    }
    @Test fun completeCacheIsNotInvalidated() {
        files("uuid","am/final.mdl","conf/mfcc.conf","graph/HCLG.fst")
        assertFalse(VoskModelFiles.invalidateIncompleteCache(folder.root)); assertTrue(File(folder.root,"uuid").exists())
    }
    @Test fun pathMatchesVoskStorageService03545() {
        assertEquals(File(folder.root,"model/model-ru-small"),VoskModelFiles.cacheDirectory(folder.root))
    }
}
