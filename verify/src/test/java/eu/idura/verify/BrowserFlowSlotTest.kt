package eu.idura.verify

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

      slot.resume("ok")
      assertEquals("ok", flow.await())
    }

  @Test
  fun `run rethrows the exception passed to fail`() =
    runTest {
      val slot = BrowserFlowSlot<String>()
      val flow = async { runCatching { slot.run { } } }
      testScheduler.runCurrent()

      slot.fail(IllegalArgumentException("boom"))

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
  fun `resume is a no-op when no flow is in progress`() =
    runTest {
      val slot = BrowserFlowSlot<String>()
      slot.resume("nobody listening")
    }

  @Test
  fun `fail is a no-op when no flow is in progress`() =
    runTest {
      val slot = BrowserFlowSlot<String>()
      slot.fail(Exception("nobody listening"))
    }
}
