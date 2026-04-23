@file:OptIn(ExperimentalEncodingApi::class)

package eu.idura.verify.eid

import eu.idura.verify.Action
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

sealed class EID<T : EID<T>>(
  acrValue: String,
  internal val scopes: MutableSet<String> = mutableSetOf(),
  internal val loginHints: MutableSet<String> = mutableSetOf(),
) {
  private var acrValues = mutableListOf(acrValue)
  internal var action: Action? = null

  protected fun withModifier(modifier: String): T {
    acrValues.add(modifier.lowercase())
    return getThis()
  }

  // Allows us to return a concrete subtype for chaining, while defining the actual methods on the abstract class. https://stackoverflow.com/questions/5818504/can-i-have-an-abstract-builder-class-in-java-with-method-chaining-without-doing
  protected abstract fun getThis(): T

  fun withScope(scope: String): T {
    this.scopes.add(scope)
    return getThis()
  }

  fun withLoginHint(loginHint: String): T {
    this.loginHints.add(loginHint)
    return getThis()
  }

  protected open fun withAction(action: Action): T {
    this.action = action
    return getThis()
  }

  protected open fun withMessage(message: String) =
    withLoginHint("message:${Base64.encode(message.toByteArray())}")

  internal val acrValue: String
    get() = acrValues.joinToString(":")

  /**
   * Whether this eID's login supports an app-switch redirection back to the consumer app.
   *
   * Derived from the `acrValue` prefix so that `Other("urn:grn:authn:dk:mitid:substantial")`
   * and the same value arrived at via `DanishMitID.substantial()` behave identically. Subclasses
   * can override if they ever need to disagree with the prefix-based default, but the intended
   * extension point is the [APP_SWITCH_ACR_PREFIXES] list below.
   */
  internal open val supportsAppSwitch: Boolean
    get() = APP_SWITCH_ACR_PREFIXES.any { acrValue.startsWith(it) }
}

private val APP_SWITCH_ACR_PREFIXES =
  listOf(
    "urn:grn:authn:dk:mitid",
    "urn:grn:authn:se:frejaid",
  )
