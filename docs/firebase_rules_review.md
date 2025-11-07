# Firestore rule expectations for Diana

This note captures the Firestore access patterns exercised by the Android client and the
implications for the security rules. It is intended to help diagnose and correct
`PERMISSION_DENIED` errors observed when redeeming invite QR codes.

## Client behaviour

The invite redemption flow performs these Firestore operations:

1. **Look up the invite metadata.**
   The client queries the `pending_invites` collection using the SHA-256 hash of the QR payload
   *before* calling `FirebaseAuth.signInWithCustomToken(...)`.
   As a consequence the lookup is performed without any authenticated user context.【F:app/src/main/java/li/crescio/penates/diana/onboarding/InviteRedemption.kt†L29-L86】
2. **Mark the invite as redeemed.**
   Immediately after signing in with the custom token, the app updates the same Firestore
   document to flip the `redeemed` flag and record `redeemed_uid` and `redeemed_at`.  This write
   therefore executes as the newly signed-in user whose UID matches the `uid` stored in the
   document.【F:app/src/main/java/li/crescio/penates/diana/onboarding/InviteRedemption.kt†L88-L110】

Beyond onboarding, the application expects authenticated access to the following collections:

- `sessions` (including `sessions/{sessionId}/notes`), for synchronising structured notes and
  session metadata.【F:app/src/main/java/li/crescio/penates/diana/session/SessionRepository.kt†L132-L215】【F:app/src/main/java/li/crescio/penates/diana/persistence/NoteRepository.kt†L214-L236】
- `resources`, for refreshing LLM override bundles.【F:app/src/main/java/li/crescio/penates/diana/llm/LlmResources.kt†L55-L61】

These calls are made after the custom token sign-in completes, so the requests present a valid
`request.auth` whose provider is `custom`.

## Rule adjustments

To support the flows above, configure your Firestore rules to:

1. Permit unauthenticated (or anonymous) reads of `pending_invites` so the client can verify the
   invite before it signs in.
2. Allow the signed-in custom-token user whose UID matches the document to update the invite after
   redemption.
3. Recognise custom-token users (not just anonymous ones) as trusted clients for the main
   collections (`sessions`, `notes`, `resources`, etc.).

One way to satisfy these constraints is to refactor the helpers and add a specific rule block for
`pending_invites`, for example:

```firebase
function isDianaClient() {
  return request.auth != null && (
    request.auth.token.firebase.sign_in_provider == "custom" ||
    request.auth.token.firebase.sign_in_provider == "anonymous"
  );
}

match /pending_invites/{inviteId} {
  allow get, list: if request.auth == null || request.auth.token.firebase.sign_in_provider == "anonymous";
  allow update: if request.auth != null && request.auth.uid == resource.data.uid;
}

match /sessions/{sessionId} {
  allow read, write: if isDianaClient();
}

match /sessions/{sessionId}/{document=**} {
  allow read, write: if isDianaClient();
}

match /resources/{resourceId} {
  allow read: if isDianaClient();
  allow write: if isDianaClient(); // tighten further if writes should be restricted
}
```

This example keeps the invite lookup open to anonymous clients (while the stored metadata excludes
sensitive fields) and ensures that only the authenticated user associated with the invite can mark
it as redeemed. The helper then treats both anonymous and custom-token sign-ins as trusted for the
rest of the application.  If you mint different kinds of custom tokens (for example by setting
roles via custom claims) you can extend `isDianaClient()` to check the relevant claim instead of
relying solely on `sign_in_provider`.

Remember to deploy the updated security rules and, if necessary, create the composite index that
supports the `pending_invites` query (`payload_hash` with the configured `limit`).
