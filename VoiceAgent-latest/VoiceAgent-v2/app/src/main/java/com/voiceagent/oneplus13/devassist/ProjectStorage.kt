package com.voiceagent.oneplus13.devassist

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a generated project to disk as plain files (app-internal, always writable,
 * no permissions needed) and also as a single .zip copied into the public Downloads
 * collection, so it's easy to find in a Files app or move into Termux
 * (`~/storage/downloads/` after `termux-setup-storage`).
 */
class ProjectStorage(private val context: Context) {

    fun save(project: GeneratedProject): File {
        val safeName = sanitizeProjectName(project.projectName)
        val root = File(context.filesDir, "generated_projects")
        check(!java.nio.file.Files.isSymbolicLink(root.toPath()))
        val projectDir = File(root, safeName)
        check(!java.nio.file.Files.isSymbolicLink(projectDir.toPath()))
        projectDir.mkdirs()
        check(projectDir.canonicalFile.parentFile == root.canonicalFile)
        // Единственная техническая граница между тем, что пишет LLM, и остальной
        // файловой системой: даже если модель ошибётся или промпт когда-нибудь
        // обманут, сгенерированный файл физически не может выйти за пределы
        // своей же папки проекта — а значит не может дотянуться до кода самого
        // VoiceAgent или чужих данных на телефоне (см. isPathSafe).
        val safeFiles = project.files.filter { isPathSafe(projectDir, it.relativePath) }
        safeFiles.forEach { file ->
            val target = File(projectDir, file.relativePath)
            target.parentFile?.mkdirs()
            target.writeText(file.content)
        }
        ensureGitignore(projectDir, safeFiles)
        File(projectDir, PLATFORM_META_FILE).writeText(project.platform)
        val zip = zipProject(projectDir, safeName)
        copyToDownloads(zip, "$safeName.zip")
        return zip
    }

    /** Отклоняет абсолютные пути и любые "../" — не даёт файлу из сгенерированного
     *  проекта записаться выше собственной папки проекта. */
    private fun isPathSafe(projectDir: File, relativePath: String): Boolean {
        if (relativePath.isBlank() || File(relativePath).isAbsolute || relativePath.split('/').any { it == ".." }) return false
        val root = projectDir.canonicalFile
        val candidate = File(projectDir, relativePath).canonicalFile
        return candidate.path.startsWith(root.path + File.separator)
    }

    /** Файлы уже сохранённого проекта — для "продолжить проект" (CodeGenAgent.revise),
     *  без .gitignore, без служебной метки платформы и без соседнего .zip (он лежит
     *  рядом с папкой, не внутри неё). */
    /**
     * Read only regular files inside the named generated project. Never let a name from
     * speech/LLM select a sibling folder or an arbitrary absolute path. Do not send
     * secrets and large files to the LLM when revising a generated project.
     */
    fun loadProjectFiles(projectName: String): List<ProjectFile>? {
        return ProjectReader(File(context.filesDir, "generated_projects")).files(projectName)
    }

    fun loadProjectPlatform(projectName: String): String {
        val projectDir = resolveStoredProjectDir(projectName) ?: return "android"
        val file = File(projectDir, PLATFORM_META_FILE)
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return "android"
        if (!isInside(projectDir, canonical) || !canonical.isFile || canonical.length() > 64) return "android"
        return canonical.readText().trim().takeIf { it == "android" || it == "windows" } ?: "android"
    }

    private fun resolveStoredProjectDir(projectName: String): File? {
        return ProjectReader(File(context.filesDir, "generated_projects")).directory(projectName)
    }

    private fun isInside(root: File, candidate: File): Boolean =
        candidate.path.startsWith(root.canonicalPath + File.separator)

    /** Имена всех когда-либо сгенерированных проектов — самые новые последними. */
    fun listProjectNames(): List<String> =
        File(context.filesDir, "generated_projects")
            .listFiles { f -> f.isDirectory }
            ?.sortedBy { it.lastModified() }
            ?.map { it.name }
            ?: emptyList()

    /** Простой .gitignore по угаданному стеку — чтобы `git init && git add -A &&
     *  git commit` в Termux сразу не утащил венвы/node_modules/секреты в историю.
     *  Не трогаем, если LLM уже сгенерировала свой .gitignore. */
    private fun ensureGitignore(projectDir: File, files: List<ProjectFile>) {
        val gitignoreFile = File(projectDir, ".gitignore")
        if (gitignoreFile.exists()) return

        val looksPython = files.any { it.relativePath.endsWith(".py") || it.relativePath == "requirements.txt" }
        val looksNode = files.any { it.relativePath.endsWith(".js") || it.relativePath == "package.json" }

        val lines = buildList {
            add("# Добавлено автоматически VoiceAgent")
            add(".env")
            add("*.log")
            if (looksPython) {
                add("__pycache__/")
                add("*.pyc")
                add(".venv/")
            }
            if (looksNode) {
                add("node_modules/")
            }
        }
        gitignoreFile.writeText(lines.joinToString("\n"))
    }

    private fun zipProject(projectDir: File, projectName: String): File {
        val zipFile = File(context.filesDir, "generated_projects/$projectName.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            projectDir.walkTopDown().filter { it.isFile && it.name != PLATFORM_META_FILE }.forEach { file ->
                check(!java.nio.file.Files.isSymbolicLink(file.toPath()) && isInside(projectDir, file.canonicalFile))
                val entryName = file.relativeTo(projectDir).path
                zos.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zipFile
    }

    private fun copyToDownloads(zip: File, displayName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VoiceAgent")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { out -> zip.inputStream().use { it.copyTo(out) } }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VoiceAgent")
            dir.mkdirs()
            zip.copyTo(File(dir, displayName), overwrite = true)
        }
    }

    /** Share sheet intent so the user can send the zip straight to Termux, "Saved Messages", etc. */
    fun shareIntent(zip: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        private const val PLATFORM_META_FILE = ".voiceagent-platform"
        private const val MAX_SINGLE_FILE_BYTES = 128L * 1024
        private const val MAX_TOTAL_READ_BYTES = 512L * 1024
    }
}
