package com.voiceagent.oneplus13.core

import java.io.File

/** Structural preflight only; successful native Model initialization remains a separate check. */
object VoskModelFiles {
    const val ASSET_PATH = "model-ru-small"
    const val STORAGE_PATH = "model"
    fun missing(hasNonEmptyFile: (String) -> Boolean): List<String> = buildList {
        for (name in listOf("uuid", "am/final.mdl", "conf/mfcc.conf")) if (!hasNonEmptyFile(name)) add(name)
        if (!hasNonEmptyFile("graph/HCLG.fst") &&
            !(hasNonEmptyFile("graph/HCLr.fst") && hasNonEmptyFile("graph/Gr.fst"))) add("graph/FST")
    }
    fun missing(directory: File): List<String> = missing { File(directory, it).let { f -> f.isFile && f.length() > 0 } }
    fun cacheDirectory(externalFilesDir: File, assetPath: String = ASSET_PATH): File =
        File(File(externalFilesDir, STORAGE_PATH), assetPath)

    /** StorageService.sync trusts an equal uuid without checking the actual model files. */
    fun invalidateIncompleteCache(directory: File): Boolean {
        val marker = File(directory, "uuid")
        if (!marker.exists() || missing(directory).isEmpty()) return false
        check(marker.delete()) { "Cannot invalidate incomplete STT cache" }
        return true
    }
}
