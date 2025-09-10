# Deployment

## Firebase setup

1. Create a Firebase project named `diana`.
2. Enable Firestore, Crashlytics, and Analytics.
3. In the console add an Android app with the package
   `li.crescio.penates.diana`.
4. Download `google-services.json` and place it in `app/`.
5. Generate a service account key for CI and store it as the secret
   `FIREBASE_SERVICE_ACCOUNT`.

## GitHub Actions

A workflow builds and distributes debug builds through Firebase App
Distribution. Configure the repository with these secrets:

- `FIREBASE_SERVICE_ACCOUNT` – base64 encoded service account JSON.
- `FIREBASE_APP_ID` – the app id from Firebase.
- `FIREBASE_TESTERS_GROUPS` – comma separated tester groups.
- `GOOGLE_SERVICES_JSON` – contents of `google-services.json`.

The workflow runs on pushes to `main` and publishes the APK to the testers
group.

## Local builds

1. Install JDK 17 and the Android SDK.
2. Provide `GROQ_API_KEY` and `OPENROUTER_API_KEY` either as environment variables or in `local.properties` (e.g. `OPENROUTER_API_KEY=your_key`). If `OPENROUTER_API_KEY` is empty, memo processing is disabled.
3. Open the project in Android Studio and let Gradle sync.
4. Run `./gradlew assembleDebug` to build and install on a device.
