package eu.idura.verify.eid

import eu.idura.verify.Action
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class DanishMitID private constructor() : EID<DanishMitID>(acrValue = "urn:grn:authn:dk:mitid") {
  companion object {
    fun substantial() = DanishMitID().withModifier("substantial")

    fun high() = DanishMitID().withModifier("high")

    fun low() = DanishMitID().withModifier("low")

    fun business() = DanishMitID().withModifier("business")
  }

  override fun getThis(): DanishMitID = this

  fun prefillSsn(ssn: String) = withScope("ssn").withLoginHint("sub:$ssn")

  /**
   * Prefilling the UUID allows the user to skip entering their username, https://docs.idura.com/verify/e-ids/danish-mitid/#reauthentication
   */
  @OptIn(ExperimentalUuidApi::class)
  fun prefillUUID(uuid: Uuid) = prefillUUID(uuid.toString())

  /**
   * Prefilling the UUID allows the user to skip entering their username, https://docs.idura.com/verify/e-ids/danish-mitid/#reauthentication
   */
  fun prefillUUID(uuid: UUID) = prefillUUID(uuid.toString())

  /**
   * Prefilling the UUID allows the user to skip entering their username, https://docs.idura.com/verify/e-ids/danish-mitid/#reauthentication
   */
  fun prefillUUID(uuid: String) = withLoginHint("uuid:$uuid")

  fun prefillVatId(vatId: String) = withLoginHint("vatid:DK$vatId")

  fun withSsn() = withScope("ssn")

  fun withAddress() = withScope("address")

  public override fun withMessage(message: String) = super.withMessage(message)

  public override fun withAction(action: Action) = super.withAction(action)
}
