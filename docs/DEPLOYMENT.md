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

## Managing LLM resources

The uploader script in `scripts/upload_llm_resources.py` keeps Firestore in sync with the
canonical LLM resource files stored in `app/src/main/resources/llm`. Run it whenever
prompt templates or other runtime assets change so the remote collection matches the
checked-in tree.

### Prerequisites

- Python 3.
- Install dependencies: `pip install firebase-admin` (a virtual environment is
  recommended).
- A Firebase service-account JSON file with Firestore write access. Store the file in a
  secrets manager or encrypted storage and decode it to a temporary file before running
  the script (for example: `echo "$FIREBASE_SERVICE_ACCOUNT" | base64 -d > /tmp/diana-sa.json`).

### Usage

The script mirrors the remote collection to the local directory—documents are created,
overwritten, or deleted to match the on-disk files. Preview the changes first:

```sh
python scripts/upload_llm_resources.py --credentials /tmp/diana-sa.json --dry-run
```

When the output looks correct, run it without `--dry-run`. You can also target a custom
collection (defaults to `resources`):

```sh
python scripts/upload_llm_resources.py \
  --credentials /tmp/diana-sa.json \
  --collection staging-resources
```

### Best practices

- Review `git diff` (or any other comparison tool) for changes to `app/src/main/resources/llm`
  before uploading.
- Coordinate with the team on how service-account credentials are shared, rotated, and cleaned
  up after use.
- Delete any temporary files containing credentials once synchronization completes.
