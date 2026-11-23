# Firebase setup for VeriPago

Use the dedicated Firebase project `veripago-9cda6`. The `lantonium` project belongs to the user's personal site and is not used by VeriPago.

## Already present

- Firebase Web app: `1:278453594780:web:14436b08b1f93a71711b59`.
- Firebase Android app: `1:278453594780:android:56075e322baa0f09711b59`, package `com.lantonium.veripago`.
- Cloud Firestore database `(default)`, Standard edition, in `southamerica-east1` (São Paulo), with deletion protection enabled.
- Hosting site `veripago-9cda6`, at `https://veripago-9cda6.web.app`.
- `lantonium` retains its personal-site Web app; its incorrectly registered VeriPago Android app was removed.
- The backend profile provisioner assigns `ADMINISTRADOR` to the explicitly authorized Google account `lantonium@gmail.com`; new web registrations get `COMERCIANTE`.

## Finish Google sign-in

1. In Firebase Console for `veripago-9cda6`, open **Authentication → Sign-in method → Google**, enable it, and select `lantonium@gmail.com` as the support email. The provider is not enabled yet; its REST API configuration requires OAuth client credentials that Firebase Console generates when saving the provider.
2. **Authentication → Settings → Authorized domains** already includes `localhost`, `veripago-9cda6.firebaseapp.com`, `veripago-9cda6.web.app`, and the temporary preview domain `veripago-9cda6--setup-review-c732u8hw.web.app`.
3. In **Project settings → Your apps → VeriPago Android**, the debug fingerprints are registered:
   - SHA-1: `0F:7E:CF:D2:E1:01:63:C9:6A:08:AD:22:B0:40:E2:D1:01:07:9D:4B`
   - SHA-256: `8F:2A:86:CC:31:2E:C3:69:32:4B:03:9D:6B:6A:1E:DB:51:CB:40:01:59:71:7C:AC:60:8A:56:27:C5:AB:3C:0E`
4. After Google is enabled, copy the **Web client ID** from the Google provider settings into `webClientId` in the untracked `local.properties`. The Android app uses the web OAuth client ID for Credential Manager; it does not use an Android OAuth client ID for that field.
5. For a release APK, add the release keystore SHA-1 and SHA-256 as well. The release signing key has not been created in this checkout.

The Web SDK values are prefilled in `.env.example`; copy it to `.env.local`. The `.env.local` file stays local and is excluded from Git. Firebase Web API keys and app IDs identify the client project; access is enforced by Authentication, Firestore rules, and Storage rules. New merchant/admin profiles are provisioned by the trusted callable; clients cannot write their own role. Buyers do not sign in or get an account view; they pay through the public checkout or QR.

## Firestore and Storage

- The `(default)` Firestore database exists in `veripago-9cda6`. `firestore.rules` and `firestore.indexes.json` have been deployed after emulator rule tests.
- The SDK configuration names `veripago-9cda6.firebasestorage.app`, but Firebase Storage has not been initialized and `storage.rules` have not been deployed. Initialize the bucket in **Storage → Files** in the same project and region before deploying `storage.rules`; Firebase Console may require the project to use the Blaze billing plan to create the bucket.
- The rules expect the first Google account assigned the `ADMINISTRADOR` role to manage APK releases and global settings. Do not grant that role to every new account.

## Messaging (FCM)

Cloud Messaging is not needed to read bank notifications or speak payment confirmations; those are handled locally by `NotificationListenerService` and Android TTS. To use FCM for platform announcements or merchant alerts:

1. In **Project settings → Cloud Messaging**, confirm the Firebase Cloud Messaging API (HTTP v1) is enabled.
2. Keep sending credentials on a trusted server (Firebase Admin SDK / service account); never put a server key in the Android app or Web bundle.
3. Add the Android FCM SDK, a `FirebaseMessagingService`, runtime notification permission handling, token registration in `linked_devices`, and a server-side sender for `announcements`. These pieces are not implemented yet.

## Hosting and production safety

`firebase.json` is configured to serve Vite’s `/VeriPago` build with SPA rewrites and cache/security headers. A preview-channel deployment is available at `https://veripago-9cda6--setup-review-c732u8hw.web.app/VeriPago/` until 2026-11-30. The dedicated production site is separate from `lantonium` and can be published to `https://veripago-9cda6.web.app` after Google sign-in and backend provisioning are ready.

## Local values

The Android Firebase app configuration is recorded in local `local.properties` except for `webClientId`, which Firebase supplies after Google sign-in is enabled. Do not commit that file, `.env.local`, Google OAuth client secrets, or a release keystore.
