package com.voiceagent.oneplus13.devassist

import java.io.File
import java.nio.file.Files

/** Reads only a named direct child. Symlinks are rejected rather than followed. */
internal class ProjectReader(private val root: File) {
    fun directory(name: String): File? {
        if (!name.matches(Regex("[A-Za-z0-9_-]+")) || Files.isSymbolicLink(root.toPath())) return null
        val path = File(root, name)
        if (Files.isSymbolicLink(path.toPath())) return null
        return path.canonicalFile.takeIf { it.isDirectory && it.parentFile == root.canonicalFile }
    }
    fun files(name: String): List<ProjectFile>? {
        val dir = directory(name) ?: return null
        val result = mutableListOf<ProjectFile>()
        var total = 0L
        var count = 0
        fun visit(folder: File, depth: Int): Boolean {
            if (depth > 20) return false
            for (file in folder.listFiles() ?: return false) {
                if (++count > 2000 || Files.isSymbolicLink(file.toPath())) return false
                if (!file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) return false
                val relative = file.relativeTo(dir).invariantSeparatorsPath
                if (sensitive(relative) || relative == ".voiceagent-platform") continue
                if (file.isDirectory) { if (!visit(file, depth + 1)) return false }
                else if (file.isFile) {
                    if (file.length() > 128 * 1024 || total + file.length() > 512 * 1024) return false
                    total += file.length()
                    result += ProjectFile(relative, file.readText(Charsets.UTF_8))
                }
            }
            return true
        }
        return runCatching { if (visit(dir, 0)) result else null }.getOrNull()
    }
    companion object {
        fun sensitive(path: String): Boolean {
            val parts = path.lowercase().split('/')
            if (parts.any { it in setOf(".git", ".ssh", ".aws", "node_modules", ".venv") }) return true
            val name = parts.last()
            return name == ".env" || name.startsWith(".env.") ||
                name in setOf("id_rsa", "id_ed25519", "credentials", "credentials.json", "secrets.json", "local.properties") ||
                listOf(".pem", ".key", ".p12", ".jks", ".keystore").any { name.endsWith(it) }
        }
    }
}
