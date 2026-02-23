package eu.idura.verify.eid

import eu.idura.verify.Action

class SwedishBankID private constructor() :
  EID<SwedishBankID>(acrValue = "urn:grn:authn:se:bankid") {
    companion object {
      fun otherDevice() = SwedishBankID().withModifier("another-device:qr")

      fun sameDevice() = SwedishBankID().withModifier("same-device")

      fun selectorPage() = SwedishBankID()
    }

    override fun getThis(): SwedishBankID = this

    fun withSsn(ssn: String) = withLoginHint("sub:$ssn")

    public override fun withMessage(message: String) = super.withMessage(message)

    fun sign(message: String) = withMessage(message).withAction(Action.Sign)
  }
