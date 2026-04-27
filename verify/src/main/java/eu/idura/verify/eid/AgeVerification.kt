package eu.idura.verify.eid

enum class AgeVerificationCountry(
  internal val value: String,
) {
  Denmark("DK"),
  Sweden("SE"),
  Norway("NO"),
  Finland("FI"),
}

enum class AgeVerificationAge(
  internal val age: Int,
) {
  Over15(15),
  Over16(16),
  Over18(18),
  Over21(21),
}

class AgeVerification private constructor() :
  EID<AgeVerification>(acrValue = "urn:age-verification") {
    companion object {
      fun over(age: AgeVerificationAge) = AgeVerification().over(age)
    }

    override fun getThis(): AgeVerification = this

    fun over(age: AgeVerificationAge) = withScope("is_over_${age.age}")

    fun withCountry(country: AgeVerificationCountry) = withLoginHint("country:${country.value}")
  }
