package com.voiceagent.oneplus13.lecture

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class LectureSummaryRecoveryTest {
    @get:Rule val folder=TemporaryFolder()
    private fun repo()=LectureRepository(folder.root)
    private fun session()=LectureSession(title="Recovered",startedAtMillis=100,chunks=mutableListOf("Saved words")).also{repo().save(it)}
    @Test fun failureKeepsTextAndNewInstanceCanRetry()=runBlocking {
        val session=session()
        try { LectureSummaryRecovery(repo(),{throw IOException("synthetic offline")}){200}.run(session.id);fail("must fail") } catch(_:IOException){}
        val checkpoint=repo().load(session.id)!!;assertEquals("Saved words",checkpoint.fullTranscript());assertTrue(checkpoint.interrupted);assertEquals(200L,checkpoint.endedAtMillis)
        assertEquals(SummaryResult.SAVED,LectureSummaryRecovery(repo(),{"Result"}).run(session.id))
        assertEquals("Result",repo().load(session.id)!!.summary)
    }
    @Test fun duplicatesWaitAndReuseSavedSummary()=runBlocking {
        val session=session();var calls=0;val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val recovery=LectureSummaryRecovery(repo(),{calls++;entered.complete(Unit);finish.await();"Result"})
        val first=async{recovery.run(session.id)};entered.await()
        val second=async(start=CoroutineStart.UNDISPATCHED){recovery.run(session.id)}
        finish.complete(Unit)
        assertEquals(SummaryResult.SAVED,first.await());assertEquals(SummaryResult.ALREADY_SAVED,second.await());assertEquals(1,calls)
    }
    @Test fun cancellationLeavesRetryableTranscriptAndReleasesLock()=runBlocking {
        val session=session();val entered=CompletableDeferred<Unit>();var first=true
        val recovery=LectureSummaryRecovery(repo(),{if(first){first=false;entered.complete(Unit);awaitCancellation()};"Retried"})
        val job=launch{recovery.run(session.id)};entered.await();job.cancelAndJoin()
        assertNull(repo().load(session.id)!!.summary);assertEquals("Saved words",repo().load(session.id)!!.fullTranscript())
        assertEquals(SummaryResult.SAVED,recovery.run(session.id))
    }
    @Test fun emptyTranscriptAndBlankResponseNeverBecomeSuccessfulSummary()=runBlocking {
        val empty=LectureSession(title="empty");repo().save(empty)
        assertEquals(SummaryResult.EMPTY,LectureSummaryRecovery(repo(),{error("No paid request")}).run(empty.id))
        val session=session()
        try{LectureSummaryRecovery(repo(),{" "}).run(session.id);fail("must fail")}catch(_:IllegalStateException){}
        assertNull(repo().load(session.id)!!.summary)
    }
    @Test fun queuedDifferentLecturesBothGetSaved()=runBlocking {
        val first=session();val second=session();val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>();var calls=0
        val recovery=LectureSummaryRecovery(repo(),{calls++;if(calls==1){entered.complete(Unit);finish.await()};"Result ${it.id}"})
        val one=async{recovery.run(first.id)};entered.await();val two=async{recovery.run(second.id)};finish.complete(Unit)
        one.await();two.await();assertEquals(2,calls);assertNotNull(repo().load(first.id)!!.summary);assertNotNull(repo().load(second.id)!!.summary)
    }
}
