# Example

This example application shows you how to integrate the Idura Verify Android SDK.

Note that eIDs which use authenticator apps (Danish MitID, Swedish and Norwegian BanKID) cannot be used in a simulator. So in order to test the full experience, you should have access to a physical Android device.

1. Clone this repo.
2. Open the repo in Android Studio.
3. Create a domain in the Idura dashboard, if you haven't done so already.
4. Create a new Verify application in the Idura dashboard.
   1. Add `https://[YOUR DOMAIN]/android/callback` as redirect URL.
   2. In the Native / Mobile section, set "App package name" to `eu.idura.verifyexample` (since this application will not be published, you don't need to use your own package name).
   3. Run `./gradlew signingReport` to get the key used to sign your local debug build, and add it to the "SHA256 fingerprints" section.
5. Add your domain and client ID to the `gradle.properties` file:
   ```env
   iduraDomain=this-is-an-example.criipto.id
   iduraClientId=urn:my:application:identifier:XXXX
   ```
6. Run the application
7. Run a mock login, to verify that everything works as expected
8. :tada:

The mock provider works out of the box, but other eID providers require you to register test users before you can run a login. See https://docs.idura.app/verify/e-ids/.

## The plain-`Activity` host

`MainActivity` is a `ComponentActivity`, which is what most apps have and what needs no extra work from you. `PlainHostActivity` is the same login on a plain `android.app.Activity`, where the host must drive its own `Lifecycle` and forward `onActivityResult` to the SDK. It is the smallest complete example of that path; see the SDK README section on hosts that are not a `ComponentActivity`.

It has no launcher icon and nothing in the app links to it: it exists for the instrumented tests to drive, and a second launcher activity would make them ambiguous about which host they started.

To poke at it by hand, set `android:exported="true"` on it in `AndroidManifest.xml` and then `adb shell am start -n eu.idura.verifyexample/.PlainHostActivity`. Don't commit that — the shell user cannot start an unexported activity, which is the only reason the flag would need changing.
