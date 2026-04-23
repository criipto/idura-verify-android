@file:OptIn(ExperimentalEncodingApi::class)

package eu.idura.verify.eid

import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class Mock : EID<Mock>(acrValue = "urn:grn:authn:mock") {
  override fun getThis(): Mock = this

  /**
   * Provide an object of mock data, which will be inserted into the returned JWT.
   * Relies on kotlinx serialization, so you must enable serialization in your project, see https://kotlinlang.org/docs/serialization.html#example-json-serialization
   *
   * Alternatively, you can pass an already stringified object.
   */
  inline fun <reified T> withMockData(data: T): Mock = withMockData(Json.encodeToString<T>(data))

  /**
   * Provide a JSON stringified object of mock data, which will be inserted into the returned JWT
   */
  fun withMockData(data: String): Mock = withLoginHint("mock:${Base64.encode(data.toByteArray())}")
}
