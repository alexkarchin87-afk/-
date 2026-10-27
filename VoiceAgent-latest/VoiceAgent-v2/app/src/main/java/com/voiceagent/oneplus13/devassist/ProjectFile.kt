package com.voiceagent.oneplus13.devassist

/** One file within a generated project: relative path + full text content. */
data class ProjectFile(val relativePath: String, val content: String)

/** Result of a code-generation request: a named project, its files, and short
 *  human-readable instructions for how to run it (since VoiceAgent itself does
 *  not execute arbitrary generated code — see ProjectStorage). [platform] is
 *  "android" or "windows" — fixed at creation, revise() keeps it as-is so a
 *  project can't accidentally switch stacks mid-way. */
data class GeneratedProject(
    val projectName: String,
    val files: List<ProjectFile>,
    val runInstructions: String,
    val requiredSecrets: List<String> = emptyList(), // e.g. ["TELEGRAM_BOT_TOKEN"]
    val platform: String = "android"
)
