package li.crescio.penates.diana.onboarding

import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException

class InviteValidationException(message: String) : Exception(message)

data class InviteRedemptionResult(
    val inviteId: String,
)

suspend fun redeemInvite(
    encodedPayload: String,
    firestore: FirebaseFirestore,
    auth: FirebaseAuth,
): InviteRedemptionResult {
    val payload = parseInvitePayload(encodedPayload)
    val now = Instant.now()
    if (now.isAfter(payload.expiresAt)) {
        throw InviteValidationException(
            "This invite expired at ${payload.expiresAt}. Request a new invite.",
        )
    }

    val payloadHash = computePayloadHash(payload)
    val snapshot = firestore
        .collection("pending_invites")
        .whereEqualTo("payload_hash", payloadHash)
        .limit(1)
        .get()
        .await()
    val document = snapshot.documents.firstOrNull()
        ?: throw InviteValidationException("Invite not found or already redeemed.")

    if (document.getBoolean("redeemed") == true) {
        throw InviteValidationException("This invite has already been used.")
    }

    val storedNonce = document.getString("nonce")
        ?: throw InviteValidationException("Invite is missing a nonce.")
    if (storedNonce != payload.nonce) {
        throw InviteValidationException("Invite validation failed. Please try again.")
    }

    val storedUid = document.getString("uid")
    if (storedUid != null && storedUid != payload.uid) {
        throw InviteValidationException("Invite validation failed. Please try again.")
    }

    val firestoreExpiry = document.getTimestamp("expires_at")?.toDate()?.toInstant()
    if (firestoreExpiry != null && now.isAfter(firestoreExpiry)) {
        throw InviteValidationException("This invite has expired. Request a new invite.")
    }

    try {
        auth.signInWithCustomToken(payload.token).await()
    } catch (error: FirebaseAuthException) {
        throw InviteValidationException(
            "Authentication failed (${error.errorCode}). Request a new invite.",
        )
    } catch (error: Exception) {
        throw InviteValidationException(
            "Authentication failed. ${error.message ?: "Please try again."}",
        )
    }

    val updateData = mutableMapOf<String, Any>(
        "redeemed" to true,
        "redeemed_at" to FieldValue.serverTimestamp(),
    )
    auth.currentUser?.uid?.let { uid -> updateData["redeemed_uid"] = uid }
    document.reference.update(updateData).await()

    return InviteRedemptionResult(document.id)
}

private data class InvitePayload(
    val token: String,
    val expiresAtIso: String,
    val expiresAt: Instant,
    val nonce: String,
    val uid: String,
    val customClaims: Map<String, Any?>,
)

private fun parseInvitePayload(encodedPayload: String): InvitePayload {
    val decoded = try {
        Base64.decode(encodedPayload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    } catch (error: IllegalArgumentException) {
        throw InviteValidationException("The scanned code is not a valid invite.")
    }
    val jsonString = decoded.toString(Charsets.UTF_8)
    val jsonObject = try {
        JSONObject(jsonString)
    } catch (error: JSONException) {
        throw InviteValidationException("Invite payload is malformed.")
    }

    val token = jsonObject.optString("token")
    if (token.isNullOrEmpty()) {
        throw InviteValidationException("Invite payload is missing a token.")
    }

    val expiresAtIso = jsonObject.optString("expires_at")
    if (expiresAtIso.isNullOrEmpty()) {
        throw InviteValidationException("Invite payload is missing an expiration time.")
    }
    val expiresAt = try {
        Instant.parse(expiresAtIso)
    } catch (error: DateTimeParseException) {
        throw InviteValidationException("Invite expiration is invalid.")
    }

    val nonce = jsonObject.optString("nonce")
    if (nonce.isNullOrEmpty()) {
        throw InviteValidationException("Invite payload is missing a nonce.")
    }

    val uid = jsonObject.optString("uid")
    if (uid.isNullOrEmpty()) {
        throw InviteValidationException("Invite payload is missing a UID.")
    }

    val customClaims = jsonObject.optJSONObject("custom_claims")?.toMap() ?: emptyMap()

    return InvitePayload(
        token = token,
        expiresAtIso = expiresAtIso,
        expiresAt = expiresAt,
        nonce = nonce,
        uid = uid,
        customClaims = customClaims,
    )
}

private fun computePayloadHash(payload: InvitePayload): String {
    val normalized = normalizeJson(
        mapOf(
            "token" to payload.token,
            "expires_at" to payload.expiresAtIso,
            "nonce" to payload.nonce,
            "uid" to payload.uid,
            "custom_claims" to payload.customClaims,
        ),
    )
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalized.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }
}

private fun normalizeJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> JSONObject.quote(value)
    is Number -> value.toString()
    is Boolean -> value.toString()
    is Map<*, *> -> {
        value.entries
            .filter { it.key is String }
            .sortedBy { it.key as String }
            .joinToString(prefix = "{", postfix = "}", separator = ", ") { entry ->
                val key = entry.key as String
                "${JSONObject.quote(key)}: ${normalizeJson(entry.value)}"
            }
    }
    is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ", ") { element ->
        normalizeJson(element)
    }
    is Array<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ", ") { element ->
        normalizeJson(element)
    }
    else -> JSONObject.wrap(value)?.let { normalizeJson(it) }
        ?: JSONObject.quote(value.toString())
}

private fun JSONObject.toMap(): Map<String, Any?> = buildMap {
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        put(key, unwrapJsonValue(opt(key)))
    }
}

private fun JSONArray.toList(): List<Any?> {
    val size = length()
    return buildList(size) {
        for (index in 0 until size) {
            add(unwrapJsonValue(opt(index)))
        }
    }
}

private fun unwrapJsonValue(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONObject -> value.toMap()
    is JSONArray -> value.toList()
    else -> value
}
