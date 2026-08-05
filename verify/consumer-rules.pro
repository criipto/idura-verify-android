# OpenTelemetry pulls in AutoValue and Error Prone annotations (both compile-time
# only, CLASS retention) and has soft references to the optional
# opentelemetry-api-incubator artifact, which the SDK does not depend on. Suppress
# the resulting missing-class warnings so consumers don't have to discover and
# silence them themselves.
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.errorprone.annotations.**
-dontwarn io.opentelemetry.api.incubator.trace.ExtendedSpanBuilder
-dontwarn io.opentelemetry.api.incubator.trace.ExtendedTracer

# auth0's java-jwt parses ID tokens via Jackson, which relies on reflection over
# its own classes and on the JVM Signature attribute to capture parameterised
# types from anonymous TypeReference subclasses. Keep Jackson and java-jwt intact
# rather than chase individual reflection points.
-keep class com.fasterxml.jackson.** { *; }
-keep class com.auth0.jwt.** { *; }
-dontwarn com.fasterxml.jackson.databind.ext.**
