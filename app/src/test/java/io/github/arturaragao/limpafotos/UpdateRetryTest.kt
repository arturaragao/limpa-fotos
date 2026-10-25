package io.github.arturaragao.limpafotos

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class UpdateRetryTest {
    // Bug 24/10/2026: "Software caused connection abort" no meio do APK de 93 MB virou notificação de falha.
    @Test fun connectionAbortIsQuietUntilManyFailures() {
        val abort = SocketException("Software caused connection abort")
        assertTrue(UpdateRetry.isTransient(abort))
        for (n in 1 until UpdateRetry.NOTIFY_AFTER) assertFalse(UpdateRetry.shouldNotify(abort, n))
        assertTrue(UpdateRetry.shouldNotify(abort, UpdateRetry.NOTIFY_AFTER))
    }
    @Test fun networkErrorsAreTransient() {
        assertTrue(UpdateRetry.isTransient(SocketTimeoutException()))
        assertTrue(UpdateRetry.isTransient(UnknownHostException()))
        assertTrue(UpdateRetry.isTransient(RuntimeException("x", IOException("inner"))))
    }
    @Test fun realProblemsNotifyAtOnce() {
        val bad = IllegalStateException("APK não pertence ao Limpa Fotos")
        assertFalse(UpdateRetry.isTransient(bad))
        assertTrue(UpdateRetry.shouldNotify(bad, 1))
    }
    @Test fun resumeFollowsServerAnswer() {
        assertEquals(true, UpdateRetry.appendFor(206, asked = true))
        assertEquals(false, UpdateRetry.appendFor(200, asked = true))
        assertEquals(false, UpdateRetry.appendFor(200, asked = false))
        assertNull(UpdateRetry.appendFor(416, asked = true))
        assertThrows(IOException::class.java) { UpdateRetry.appendFor(503, asked = true) }
        assertThrows(IOException::class.java) { UpdateRetry.appendFor(206, asked = false) }
    }
}
