# Firebase setup for VeriPago

Use the existing Firebase project `lantonium`. It owns the requested `lantonium.com/VeriPago` URL. The separate project `veripago-9cda6` is not the project used by that domain.

## Already present

- Firebase Web app: `1:96728900537:web:4755ece9834394c6f40c68`.
- Firebase Android app: `1:96728900537:android:0d2b3db1d1af2174f40c68`, package `com.lantonium.veripago`.
- Cloud Firestore database `(default)`.
- Hosting site `lantonium`. Its root currently serves the Lantonium portfolio; `/VeriPago/` currently returns 404.

## Finish Google sign-in

1. In Firebase Console for `lantonium`, open **Authentication → Sign-in method → Google**, enable it, and select the support email.
2. In **Authentication → Settings → Authorized domains**, ensure `lantonium.com`, `lantonium.firebaseapp.com`, `lantonium.web.app`, and `localhost` are present. Add the temporary Hosting preview domain only if signing in from that preview.
3. In **Project settings → Your apps → VeriPago Android**, add the debug fingerprints:
   - SHA-1: `0F:7E:CF:D2:E1:01:63:C9:6A:08:AD:22:B0:40:E2:D1:01:07:9D:4B`
   - SHA-256: `8F:2A:86:CC:31:2E:C3:69:32:4B:03:9D:6B:6A:1E:DB:51:CB:40:01:59:71:7C:AC:60:8A:56:27:C5:AB:3C:0E`
4. After Google is enabled, copy the **Web client ID** from the Google provider settings into `webClientId` in the untracked `local.properties`. The Android app uses the web OAuth client ID for Credential Manager; it does not use an Android OAuth client ID for that field.
5. For a release APK, add the release keystore SHA-1 and SHA-256 as well. The release signing key has not been created in this checkout.

The Web SDK values are prefilled in `.env.example`; copy it to `.env.local`. The `.env.local` file stays local and is excluded from Git. Firebase Web API keys and app IDs identify the client project; access is enforced by Authentication, Firestore rules, and Storage rules.

## Firestore and Storage

- The `(default)` Firestore database exists. Review and deploy `firestore.rules` and `firestore.indexes.json` to project `lantonium` after testing them in the emulator.
- The Android and Web SDK configuration names the bucket `lantonium.appspot.com`. Verify in **Storage → Files** that the bucket has been initialized. If it has not, create it in the same Firebase project and use the project’s existing data location; then deploy `storage.rules`.
- The rules expect the first Google account assigned the `ADMINISTRADOR` role to manage APK releases and global settings. Do not grant that role to every new account.

## Messaging (FCM)

Cloud Messaging is not needed to read bank notifications or speak payment confirmations; those are handled locally by `NotificationListenerService` and Android TTS. To use FCM for platform announcements or merchant alerts:

1. In **Project settings → Cloud Messaging**, confirm the Firebase Cloud Messaging API (HTTP v1) is enabled.
2. Keep sending credentials on a trusted server (Firebase Admin SDK / service account); never put a server key in the Android app or Web bundle.
3. Add the Android FCM SDK, a `FirebaseMessagingService`, runtime notification permission handling, token registration in `linked_devices`, and a server-side sender for `announcements`. These pieces are not implemented yet.

## Hosting and production safety

`firebase.json` is configured to serve Vite’s `/VeriPago` build with SPA rewrites and cache/security headers. A preview-channel deployment is safe for review. Do not deploy this repository directly to the production Hosting site yet: Firebase Hosting publishes a complete site version, and the current `lantonium` site contains the portfolio at `/`. A production rollout must combine the existing portfolio site output with `dist/VeriPago`, or use another agreed hosting architecture, so the root site remains intact.

## Local values

The Android Firebase app configuration is recorded in local `local.properties` except for `webClientId`, which Firebase supplies after Google sign-in is enabled. Do not commit that file, `.env.local`, Google OAuth client secrets, or a release keystore.
