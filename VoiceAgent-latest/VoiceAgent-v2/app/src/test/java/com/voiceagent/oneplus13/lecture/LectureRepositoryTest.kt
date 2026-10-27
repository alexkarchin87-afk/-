package com.voiceagent.oneplus13.lecture

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LectureRepositoryTest {
    @get:Rule val folder=TemporaryFolder()
    private fun repo()=LectureRepository(folder.root)
    @Test fun freshRepositoryRecoversEveryFieldAndUnicodeChunk() {
        val session=LectureSession(title="Лекция \"один\"",startedAtMillis=100,chunks=mutableListOf("Текст\nстрока", "часть 2"))
        repo().save(session)
        assertEquals(session,repo().latest());assertNull(repo().load(session.id)!!.endedAtMillis)
        assertEquals(session.fullTranscript(),File(folder.root,"${session.id}_transcript.txt").readText())
    }
    @Test fun interruptedAtomicWriteKeepsLastCommittedCheckpoint() {
        val session=LectureSession(title="one",chunks=mutableListOf("committed"));repo().save(session)
        val failed=LectureRepository(folder.root,write={ file,_ -> File(file.parentFile,".lecture-interrupted.tmp").writeText("{partial");throw IOException("synthetic disk full") })
        session.appendTranscriptChunk("uncommitted")
        assertThrows(IOException::class.java){failed.save(session)}
        assertEquals("committed",repo().load(session.id)!!.fullTranscript())
        assertEquals("committed",repo().latest()!!.fullTranscript())
    }
    @Test fun mirrorFailureDoesNotUndoCommittedText() {
        val session=LectureSession(title="one",chunks=mutableListOf("durable"));var failures=0
        val store=LectureRepository(folder.root,write={file,text->if(file.name.endsWith(".txt"))throw IOException("mirror");writeLectureAtomic(file,text)},mirrorFailure={failures++})
        store.save(session);assertEquals(1,failures);assertEquals("durable",repo().latest()!!.fullTranscript())
    }
    @Test fun latestIsByRecordingTimeAndDoesNotReturnAnOlderSummary() {
        val older=LectureSession(title="old",startedAtMillis=100,summary="old summary")
        val newer=LectureSession(title="new",startedAtMillis=200)
        repo().save(newer);repo().save(older)
        assertEquals(newer.id,repo().latest()!!.id);assertNull(repo().latest()!!.summary)
    }
    @Test fun legacyTranscriptAndAtomicFileBackupRemainReadable() {
        val id=java.util.UUID.randomUUID().toString()
        File(folder.root,"${id}_transcript.txt").writeText("incomplete")
        File(folder.root,"${id}_transcript.txt.bak").writeText("old committed text")
        File(folder.root,"${id}_summary.txt").writeText("summary")
        val session=repo().latest()!!
        assertEquals("old committed text",session.fullTranscript());assertEquals("summary",session.summary)
        repo().save(session);assertEquals(session,repo().load(id))
    }
    @Test fun malformedCheckpointIsReportedWithoutDeletingAnything() {
        val session=LectureSession(title="one");repo().save(session)
        val file=File(folder.root,"${session.id}.session.json");file.writeText("{broken")
        assertThrows(Exception::class.java){repo().latest()};assertEquals("{broken",file.readText())
    }
    @Test fun rejectsTraversalAndOutsideSymlink() {
        assertThrows(IllegalArgumentException::class.java){repo().load("../outside")}
        val outside=folder.newFile("outside");val dir=folder.newFolder("data");val id=java.util.UUID.randomUUID().toString()
        java.nio.file.Files.createSymbolicLink(File(dir,"$id.session.json").toPath(),outside.toPath())
        assertThrows(IllegalArgumentException::class.java){LectureRepository(dir).load(id)}
    }
}
