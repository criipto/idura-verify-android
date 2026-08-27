package eu.idura.verify

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserFlowSlotTest {
  @Test
  fun `run invokes launch and suspends until resumed`() =
    runTest {
      val slot = BrowserFlowSlot<String>()
      var launchCount = 0
      val flow = async { slot.run { launchCount++ } }

      testScheduler.runCurrent()
      assertEquals(1, launchCount)
      assertTrue(flow.isActive)

      assertTrue(slot.resume("ok"))
      assertEquals("ok", flow.await())
    }

  @Test
  fun `run rethrows the exception passed to fail`() =
    runTest {
      val slot = BrowserFlowSlot<String>()
      val flow = async { runCatching { slot.run { } } }
      testScheduler.runCurrent()

      assertTrue(slot.fail(IllegalArgumentException("boom")))

      val ex = flow.await().exceptionOrNull()
      assertTrue("expected IllegalArgumentException, got $ex", ex is IllegalArgumentException)
      assertEquals("boom", ex?.message)
    }

  @Test
  fun `starting a second flow while one is in progress throws`() =
    runTest {
      val slot = BrowserFlowSlot<String>()
      val first = async { slot.run { } }
      testScheduler.runCurrent()

      val ex =
        try {
          slot.run { }
          null
        } catch (e: Throwable) {
          e
        }
      assertTrue("expected IllegalStateException, got $ex", ex is IllegalStateException)

      slot.resume("done")
      first.await()
    }

  @Test
  fun `slot is reusable after normal resume`() =
    runTest {
      val slot = BrowserFlowSlot<String>()
      val first = async { slot.run { } }
      testScheduler.runCurrent()
      slot.resume("first")
      assertEquals("first", first.await())

      val second = async { slot.run { } }
      testScheduler.runCurrent()
      slot.resume("second")
      assertEquals("second", second.await())
    }

  @Test
  fun `slot is reusable after fail`() =
    runTest {
      val slot = BrowserFlowSlot<String>()
      val first = async { runCatching { slot.run { } } }
      testScheduler.runCurrent()
      slot.fail(Exception("boom"))
      first.await()

      val second = async { slot.run { } }
      testScheduler.runCurrent()
      slot.resume("ok")
      assertEquals("ok", second.await())
    }

  @Test
  fun `slot is reusable after cancellation`() =
    runTest {
      val slot = BrowserFlowSlot<String>()
      val first = async { slot.run { } }
      testScheduler.runCurrent()
      first.cancelAndJoin()

      val second = async { slot.run { } }
      testScheduler.runCurrent()
      slot.resume("ok")
      assertEquals("ok", second.await())
    }

  @Test
  fun `isAwaiting follows whether a flow is parked in the slot`() =
    runTest {
      val slot = BrowserFlowSlot<String>()
      assertFalse(slot.isAwaiting)

      val flow = async { slot.run { } }
      testScheduler.runCurrent()
      assertTrue(slot.isAwaiting)

      slot.resume("ok")
      flow.await()
      assertFalse(slot.isAwaiting)
    }

  /**
   * A flow that ended in an exception or a cancellation leaves the slot empty too, so nothing can
   * read a dead flow as one still waiting for a result.
   */
  @Test
  fun `isAwaiting is false once the flow has ended, however it ended`() =
    runTest {
      val slot = BrowserFlowSlot<String>()

      val failed = async { runCatching { slot.run { } } }
      testScheduler.runCurrent()
      slot.fail(Exception("boom"))
      failed.await()
      assertFalse(slot.isAwaiting)

      val cancelled = async { slot.run { } }
      testScheduler.runCurrent()
      cancelled.cancelAndJoin()
      assertFalse(slot.isAwaiting)
    }

  @Test
  fun `resume is a no-op when no flow is in progress`() =
    runTest {
      val slot = BrowserFlowSlot<String>()
      assertFalse(slot.resume("nobody listening"))
    }

  @Test
  fun `fail is a no-op when no flow is in progress`() =
    runTest {
      val slot = BrowserFlowSlot<String>()
      assertFalse(slot.fail(Exception("nobody listening")))
    }
}
