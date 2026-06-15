package eu.idura.verifyexample

import android.util.Log
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Marks a flaky instrumented test for automatic retry. The eID app-switch flows
 * occasionally race — an external app or web page is a beat slow — surfacing as a
 * one-off [androidx.test.uiautomator.ElementNotFoundException]. A test tagged with
 * this is re-run by [RetryRule] up to [attempts] times and only fails if every
 * attempt fails. Retry masks flakiness rather than fixing it, so reach for it only
 * when the failure is genuinely a timing race, not a real regression.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Retry(
  val attempts: Int = 3,
)

/**
 * Re-runs tests annotated with [Retry]; tests without the annotation run exactly
 * once. Place it inside [CaptureOnFailure] in a RuleChain so the screenshot/hierarchy
 * dump captures the final failing attempt:
 *
 *     @get:Rule val rules = RuleChain.outerRule(CaptureOnFailure()).around(RetryRule())
 */
class RetryRule : TestRule {
  override fun apply(
    base: Statement,
    description: Description,
  ): Statement {
    val retry = description.getAnnotation(Retry::class.java) ?: return base
    return object : Statement() {
      override fun evaluate() {
        var lastError: Throwable? = null
        for (attempt in 1..retry.attempts) {
          try {
            base.evaluate()
            return
          } catch (error: Throwable) {
            lastError = error
            Log.w(
              TAG,
              "${description.displayName} failed attempt $attempt/${retry.attempts}",
              error,
            )
          }
        }
        throw lastError
          ?: IllegalStateException("@Retry(attempts=${retry.attempts}) ran no attempts")
      }
    }
  }

  private companion object {
    const val TAG = "RetryRule"
  }
}
