package eu.idura.verify.eid

class Vipps : EID<Vipps>(acrValue = "urn:grn:authn:no:vipps") {
  override fun getThis(): Vipps = this

  fun withEmail() = withScope("email")

  fun withPhone() = withScope("phone")

  fun withAddress() = withScope("address")

  fun withBirthdate() = withScope("birthdate")

  fun withSsn() = withScope("ssn")
}
