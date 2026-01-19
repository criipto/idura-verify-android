# idura-verify-android

The Idura Verify Android SDK allows your users to authenticate with a host of European eID providers. It allows your application to act as a [_public client_](https://docs.idura.com/verify/getting-started/glossary/#public-clients), meaning it does not use a client secret, but instead employs [PKCE](https://docs.idura.com/verify/getting-started/glossary/#pkce-proof-key-for-code-exchange) to ensure that a malicious actor cannot intercept the authorization code.

In addition to the basic OIDC flow, the SDK also supports [app switching](https://docs.idura.com/verify/guides/appswitch/) for the Danish MitID app.

This project is built using Kotlin, and targets Android API Level 29 and up. It builds on top of the [AppAuth library](https://github.com/openid/AppAuth-android), which is maintained by the OpenID foundation. It has been tested on Android versions 10 through 16, using Chrome (both Auth Tab and Custom Tab), Samsung Internet, Brave, and Microsoft Edge browsers.

If the end-user has one of the browsers above installed, the SDK will use that, even if it is not the default browser. If not, the SDK will fall back to the default browser. 
The SDK _can_ work with other browsers (such as Opera and Firefox). However, the end user will need to manually press "Open in app" after being redirected to the redirect URL.

# Installation

Add the following to your `build.gradle.kts` file:

```kts
implementation("eu.idura:verify:1.0.0")
```

# Usage

If you prefer a more interactive approach, there is an [example project](https://github.com/criipto/idura-verify-android/blob/master/example/README.md) which your can run and play around with.

## Initialization

The SDK needs to be configured with two pieces of information:

- Your Idura domain.
- Your Idura client ID.

The SDK assumes that:

- You will be using your [Idura domain](https://docs.idura.com/verify/getting-started/glossary/#domain-idura-domain) to host your redirect URL (both custom (vanity) domains, \*.criipto.id, and \*.idura.broker domains can be used).
- Your redirect URL will be `https://[YOUR IDURA DOMAIN]/android/callback`.

You should register the callback URL in the [Idura dashboard](https://dashboard.idura.com). To override these values, see the [Customization section](#customization).

The domain needs to be configured at compile time, since it is used to set up [intent filters](https://developer.android.com/guide/components/intents-filters#Receiving) for your redirect and app switch URLs. You do so by using [manifest placeholders](https://developer.android.com/build/manage-manifests#inject_build_variables_into_the_manifest)

The domain must also be passed when initializing the SDK class. In order to prevent drift between the value stored in your `build.gradle.kts` file and your code, we recommend adding your domain and client ID as build config fields:

```kt
android {
  buildFeatures {
    buildConfig = true
  }

  val iduraClientId = "urn:my:application:identifier:XXXX"
  val iduraDomain = "this-is-an-example.idura.broker

  defaultConfig {
    manifestPlaceholders["iduraDomain"] = iduraDomain
  }
  buildTypes {
    all {
      buildConfigField("String", "IDURA_CLIENT_ID", "\"$iduraClientId\"")
      buildConfigField("String", "IDURA_DOMAIN", "\"$iduraDomain\"")
    }
  }
}
```

You can then instantiate the SDK like this:

```kt
val iduraVerify =
  IduraVerify(
    BuildConfig.IDURA_CLIENT_ID,
    BuildConfig.IDURA_DOMAIN,
    activity = activity,
  )
```

On top of the client ID and domain, you must also specify an activity. This should be the activity that hosts your login UI. The SDK uses this activity to start the intents, which opens the browser, and shows the login UI to the user. The SDK _must_ be initialized at the same time as the containing activity. That means you should probably do something like this:

```kt
class LoginActivity : ComponentActivity() {
  val iduraVerify = IduraVerify(
    BuildConfig.IDURA_CLIENT_ID,
    BuildConfig.IDURA_DOMAIN,
    activity = this,
  )
}
```

### A word about redirect URLs

In order for redirect URLs to work, your app needs to prove that it is authorized to capture the URL. This is done by using [App Links](https://developer.android.com/training/app-links/about). When you use your Idura domain, Idura manages this for you, as long as you configure your package name and the fingerprint of your signing key in the Idura dashboard.

During development, you can get the fingerprint of your local signing key by running `./gradlew signingReport`. To get the signing key to use for release, see the [Android documentation](https://developer.android.com/training/app-links/configure-assetlinks#declare-website).

When the SDK is initialized, it will verify that your app is configured to capture the app links. If your app is _not_ correctly configured, you will see a warning like so:

```
IduraVerify   eu.idura.verifyexample  W   App link is not correctly configured for https://android-sdk.idura.broker/my/custom/callback
```

## Logging in

```kt
val jwt = iduraVerify.login(DanishMitID.substantial())
println(jwt.subject)
```

The SDK provides builder classes for some of the eIDs supported by Idura Verify. You should use these when possible, since they provide helper methods for the scopes and login hints supported by the specific eID provider. For example, Danish MitID supports SSN prefilling, which you can access using the `prefillSsn` method:

```kt
val jwt = iduraVerify.login(
  DanishMitID.substantial().prefillSsn("123456-7890").withMessage("Hello there!"),
)
```

The returned JWT class has properties for some common claims such as `subject` and `identityscheme`. For other claims, use the `getClaimsAsString`, `getClaimAsMap` etc. functions. For example, if you requested the `address` scope, you can access the address like so:

```kt
val streetAddress = jwt.getClaimAsMap("address")?.get("street_address") as? String
```

# Customization

## Using a custom callback domain

In this context, a _custom_ domain means a domain _not_ hosted by Idura. If you have registered a custom (vanity) domain in the Idura dashboard, and pointed it towards criipto.id / idura.broker, you do not need to do anything else.

If you want to use another domain, you need to host an `assetslinks.json` file on the domain, as described in the [Android documentation](https://developer.android.com/training/app-links/about).

## Using a custom callback URL

If you want to a different callback URL you need to:

1. Pass it when initializing the SDK:

```kt
IduraVerify(
  BuildConfig.IDURA_CLIENT_ID,
  BuildConfig.IDURA_DOMAIN,
  activity = activity,
  redirectUri = "https://${BuildConfig.IDURA_DOMAIN}/my/custom/callback".toUri(),
)
```

2. Update your manifest to capture the new URL:

```xml
<application>
  ...
  <activity
    android:name="eu.idura.verify.RedirectUriReceiverActivity"
    android:exported="true"
    tools:node="replace">
    <intent-filter android:autoVerify="true">
      <action android:name="android.intent.action.VIEW" />

      <category android:name="android.intent.category.DEFAULT" />
      <category android:name="android.intent.category.BROWSABLE" />

      <data android:scheme="https" />
      <data android:host="${iduraDomain}" />
      <data android:path="/my/custom/callback" />
    </intent-filter>
  </activity>
</application>

```
