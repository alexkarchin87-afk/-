package com.voiceagent.oneplus13.devassist

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectReaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun setup(): Pair<File, ProjectReader> {
        val root = temporary.newFolder("generated_projects")
        File(root, "demo").mkdirs()
        return root to ProjectReader(root)
    }
    @Test fun readsOnlyTheNamedProject() {
        val (root, reader) = setup()
        File(root, "demo/main.py").writeText("print(1)")
        assertEquals("print(1)", reader.files("demo")!!.single().content)
        for (name in listOf("..", ".", "../private", "/tmp", "demo/../demo", "", "demo\\.."))
            assertNull(name, reader.files(name))
    }
    @Test fun excludesCredentialsAndDependencyTrees() {
        val (root, reader) = setup()
        for (name in listOf(".env", ".env.local", "secret.key", "local.properties", "credentials.json"))
            File(root, "demo/$name").writeText("do-not-send")
        File(root, "demo/.git").mkdirs(); File(root, "demo/.git/config").writeText("private")
        File(root, "demo/main.kt").writeText("safe")
        assertEquals(listOf("main.kt"), reader.files("demo")!!.map { it.relativePath })
    }
    @Test fun rejectsSymlinkFilesAndDirectories() {
        val (root, reader) = setup()
        val secret = temporary.newFile("private").apply { writeText("private") }
        Files.createSymbolicLink(File(root, "demo/link").toPath(), secret.toPath())
        assertNull(reader.files("demo"))
        Files.createSymbolicLink(File(root, "alias").toPath(), File(root, "demo").toPath())
        assertNull(reader.files("alias"))
    }
    @Test fun boundsUploads() {
        val (root, reader) = setup()
        File(root, "demo/large.txt").writeText("x".repeat(128 * 1024 + 1))
        assertNull(reader.files("demo"))
    }
}
