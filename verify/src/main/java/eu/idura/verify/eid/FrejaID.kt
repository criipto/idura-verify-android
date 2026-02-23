package eu.idura.verify.eid

import eu.idura.verify.Action
import kotlin.io.encoding.Base64

abstract class FrejaID<T : FrejaID<T>> internal constructor(
  minRegistrationLevel: String,
) : EID<T>(acrValue = "urn:grn:authn:se:frejaid") {
  init {
    withLoginHint("minregistrationlevel:$minRegistrationLevel")
  }

  companion object {
    fun basic() = FrejaIDBasic("basic")

    fun extended() = FrejaIDExtendedOrPlus("extended")

    fun plus() = FrejaIDExtendedOrPlus("plus")
  }

  fun withEmail() = this.withScope("frejaid:email_address")

  fun withAllEmails() = this.withScope("frejaid:all_email_addresses")

  fun withPhoneNumbers() = this.withScope("frejaid:all_phone_numbers")

  fun withRegistrationLevel() = this.withScope("frejaid:registration_level")

  fun sign(
    message: String,
    title: String?,
  ): T {
    withAction(Action.Sign)
    if (title != null) {
      withLoginHint("title:${Base64.encode(title.toByteArray())}")
    }
    return withMessage(message)
  }
}

class FrejaIDBasic internal constructor(
  minRegistrationLevel: String,
) : FrejaID<FrejaIDBasic>(minRegistrationLevel) {
  override fun getThis(): FrejaIDBasic = this
}

class FrejaIDExtendedOrPlus internal constructor(
  minRegistrationLevel: String,
) : FrejaID<FrejaIDExtendedOrPlus>(minRegistrationLevel) {
  init {
    this.withLoginHint("minregistrationlevel:extended")
  }

  override fun getThis(): FrejaIDExtendedOrPlus = this

  fun withBasicUserInfo() = this.withScope("frejaid:basic_user_info")

  fun withDateOfBirth() = withScope("frejaid:date_of_birth")

  fun withAge() = withScope("frejaid:age")

  fun withSsn() = withScope("frejaid:ssn")

  fun withAddresses() = withScope("frejaid:addresses")

  fun withDocument() = withScope("frejaid:document")

  fun withPhoto() = withScope("frejaid:photo")

  fun withDocumentPhoto() = withScope("frejaid:document_photo")

  fun withDefaultAndFaceConfirmation() = withLoginHint("userconfirmationmethod:defaultandface")
}
