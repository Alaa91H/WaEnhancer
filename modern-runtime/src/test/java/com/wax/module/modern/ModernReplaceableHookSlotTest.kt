package com.wax.module.modern

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression for a disappearing row-hook unhook handle on lifecycle failure. */
class ModernReplaceableHookSlotTest {
    @Test
    fun failedUnhookRemainsTrackedAndBlocksDuplicateHook() {
        val slot = ModernReplaceableHookSlot()
        val attempts = AtomicInteger()
        val first = ModernHookRegistry.Handle {
            if (attempts.incrementAndGet() == 1) {
                throw IllegalStateException("synthetic first unhook failure")
            }
        }
        slot.track(first)
        val failure = slot.release()
        assertNotNull(failure)
        assertTrue(failure is IllegalStateException)
        assertTrue(slot.hasTrackedHandle())
        assertThrows(IllegalStateException::class.java) {
            slot.track(ModernHookRegistry.Handle {})
        }
        assertNull(slot.release())
        assertEquals(2, attempts.get())
        assertFalse(slot.hasTrackedHandle())
        slot.track(ModernHookRegistry.Handle { attempts.incrementAndGet() })
        assertNull(slot.release())
        assertEquals(3, attempts.get())
    }

    @Test
    fun releaseWithoutAHandleIsIdempotent() {
        val slot = ModernReplaceableHookSlot()
        assertNull(slot.release())
        assertNull(slot.release())
        assertFalse(slot.hasTrackedHandle())
    }

    @Test
    fun oldHookIsRemovedExactlyOnceBeforeNewHookCanBeTracked() {
        val slot = ModernReplaceableHookSlot()
        val order = StringBuilder()
        slot.track(ModernHookRegistry.Handle { order.append("old") })
        assertNull(slot.release())
        assertEquals("old", order.toString())
        slot.track(ModernHookRegistry.Handle { order.append("new") })
        assertNull(slot.release())
        assertEquals("oldnew", order.toString())
        assertNull(slot.release())
        assertEquals("oldnew", order.toString())
    }

    @Test
    fun simultaneousTeardownCallbacksCannotDoubleUnhook() {
        val slot = ModernReplaceableHookSlot()
        val count = AtomicInteger()
        slot.track(ModernHookRegistry.Handle { count.incrementAndGet() })
        val executor = Executors.newFixedThreadPool(8)
        val gate = CountDownLatch(1)
        try {
            val tasks =
                (0 until 24).map {
                    executor.submit<Boolean> {
                        gate.await()
                        slot.release() == null
                    }
                }
            gate.countDown()
            tasks.forEach { assertTrue(it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, count.get())
            assertFalse(slot.hasTrackedHandle())
        } finally {
            executor.shutdownNow()
        }
    }
}
