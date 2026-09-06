package com.tunnelvpn.app

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelServiceLifecycleBehaviorTest {
    @Test
    fun destroyedInstanceCannotPublishAfterReplacementClaimsOwnership() {
        val ownership = ServiceInstanceOwnership()
        val publications = mutableListOf<String>()

        ownership.claim(1L)
        ownership.claim(2L)

        assertFalse(ownership.runIfOwner(1L) { publications += "stale-disconnected" })
        assertTrue(ownership.runIfOwner(2L) { publications += "connected" })
        assertEquals(listOf("connected"), publications)
    }

    @Test
    fun ownershipCannotChangeHalfwayThroughAnAcceptedPublication() {
        val ownership = ServiceInstanceOwnership()
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val replacementClaimed = CountDownLatch(1)
        ownership.claim(1L)

        val publisher = thread(start = true) {
            ownership.runIfOwner(1L) {
                publicationEntered.countDown()
                releasePublication.await(2, TimeUnit.SECONDS)
            }
        }
        assertTrue(publicationEntered.await(2, TimeUnit.SECONDS))

        val replacement = thread(start = true) {
            ownership.claim(2L)
            replacementClaimed.countDown()
        }
        assertFalse(replacementClaimed.await(50, TimeUnit.MILLISECONDS))
        releasePublication.countDown()
        assertTrue(replacementClaimed.await(2, TimeUnit.SECONDS))

        publisher.join(2_000)
        replacement.join(2_000)
        assertFalse(ownership.isOwner(1L))
        assertTrue(ownership.isOwner(2L))
    }

    @Test
    fun destroyedInFlightStartupCannotCommitAfterReplacementClaimsOwnership() {
        val ownership = ServiceInstanceOwnership()
        val oldStartup = ServiceLifecycleCommitGuard(1L, ownership)
        val startupEntered = CountDownLatch(1)
        val releaseStartup = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val committedValues = mutableListOf<String>()
        ownership.claim(1L)

        val worker = thread(start = true) {
            startupEntered.countDown()
            releaseStartup.await(2, TimeUnit.SECONDS)
            oldStartup.runIfCurrent { committedValues += "stale-connected" }
            finished.countDown()
        }
        assertTrue(startupEntered.await(2, TimeUnit.SECONDS))

        oldStartup.destroy()
        ownership.claim(2L)
        releaseStartup.countDown()

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        worker.join(2_000)
        assertTrue(committedValues.isEmpty())
    }

    @Test
    fun destroyedInFlightStartupCannotCommitWhileInstanceStillOwnsState() {
        val ownership = ServiceInstanceOwnership()
        val startup = ServiceLifecycleCommitGuard(7L, ownership)
        val startupEntered = CountDownLatch(1)
        val releaseStartup = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var committed = false
        ownership.claim(7L)

        val worker = thread(start = true) {
            startupEntered.countDown()
            releaseStartup.await(2, TimeUnit.SECONDS)
            startup.runIfCurrent { committed = true }
            finished.countDown()
        }
        assertTrue(startupEntered.await(2, TimeUnit.SECONDS))

        startup.destroy()
        releaseStartup.countDown()

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        worker.join(2_000)
        assertTrue(ownership.isOwner(7L))
        assertFalse(committed)
    }

    @Test
    fun replacementInstanceCannotStartUntilOldInFlightStartupCleansUp() {
        val gate = ProcessLifecycleWorkGate()
        val oldStartupEntered = CountDownLatch(1)
        val releaseOldStartup = CountDownLatch(1)
        val replacementEntered = CountDownLatch(1)
        val bothFinished = CountDownLatch(2)
        val order = mutableListOf<String>()

        val oldWorker = thread(start = true) {
            gate.run {
                synchronized(order) { order += "old-start" }
                oldStartupEntered.countDown()
                releaseOldStartup.await(2, TimeUnit.SECONDS)
                synchronized(order) { order += "old-cleanup" }
            }
            bothFinished.countDown()
        }
        assertTrue(oldStartupEntered.await(2, TimeUnit.SECONDS))

        val replacementWorker = thread(start = true) {
            gate.run {
                synchronized(order) { order += "replacement-start" }
                replacementEntered.countDown()
            }
            bothFinished.countDown()
        }
        assertFalse(replacementEntered.await(50, TimeUnit.MILLISECONDS))

        releaseOldStartup.countDown()
        assertTrue(bothFinished.await(2, TimeUnit.SECONDS))
        oldWorker.join(2_000)
        replacementWorker.join(2_000)
        assertEquals(listOf("old-start", "old-cleanup", "replacement-start"), order)
    }

    @Test
    fun supersededStartupClosesHandlerMovedFromPreparedToCurrentOwnership() {
        val movedHandler = Any()
        val closed = mutableListOf<Any>()

        lifecycleResourcesToClose(
            current = movedHandler,
            prepared = null,
            previous = null
        ).forEach { handler -> closed += handler }

        assertEquals(listOf(movedHandler), closed)
    }

    @Test
    fun supersededStartupClosesEachHandlerIdentityOnlyOnce() {
        val currentAndPrepared = Any()
        val previous = Any()

        val handlers = lifecycleResourcesToClose(
            current = currentAndPrepared,
            prepared = currentAndPrepared,
            previous = previous
        )

        assertEquals(2, handlers.size)
        assertTrue(handlers[0] === currentAndPrepared)
        assertTrue(handlers[1] === previous)
    }

    @Test
    fun abandonmentCapturesCurrentHandlerBeforeClearingServiceOwnership() {
        val service = File("src/main/java/com/tunnelvpn/app/TunnelVpnService.kt").readText()
        val abandon = service.substringAfter("private fun abandonSupersededStart(")
            .substringBefore("private fun stopProtection(")

        val capture = abandon.indexOf("current = networkHandler")
        val stop = abandon.indexOf("networkHandlers.forEach")
        val clear = abandon.indexOf("networkHandler = null")
        assertTrue(capture >= 0)
        assertTrue(stop > capture)
        assertTrue(clear > stop)
    }

    @Test
    fun fatalCallbackRecordedBeforeStartupReturnsIsConsumedByStartup() {
        val failures = PendingEngineFailureRegistry<Any>()
        val candidate = Any()
        val callbackRecorded = CountDownLatch(1)

        val callback = thread(start = true) {
            failures.record(candidate, "tun-read-ended")
            callbackRecorded.countDown()
        }
        assertTrue(callbackRecorded.await(2, TimeUnit.SECONDS))

        assertEquals("tun-read-ended", failures.consume(candidate))
        assertNull(failures.consume(candidate))
        callback.join(2_000)
    }

    @Test
    fun networkCallbackRequiresCurrentHandlerEpochAndServiceOwner() {
        val activeHandler = Any()
        val retiredHandler = Any()

        assertTrue(isCurrentLifecycleCallback(activeHandler, 4L, activeHandler, 4L, true))
        assertFalse(isCurrentLifecycleCallback(activeHandler, 4L, retiredHandler, 4L, true))
        assertFalse(isCurrentLifecycleCallback(activeHandler, 4L, activeHandler, 3L, true))
        assertFalse(isCurrentLifecycleCallback(activeHandler, 4L, activeHandler, 4L, false))
    }

    @Test
    fun bothConfigRollbackPathsRestoreThePreviousDiagnosticsSnapshot() {
        val service = File("src/main/java/com/tunnelvpn/app/TunnelVpnService.kt").readText()
        val restoreCall = "previousDiagnostics?.restore(diagnostics)"

        assertEquals(2, service.windowed(restoreCall.length).count { it == restoreCall })
    }
}
