"""Provision a Firebase invite by minting a custom token and creating a pending invite document.

This script creates a Firebase Authentication custom token for an existing or new user and
stores a short-lived invitation payload in Cloud Firestore. The resulting payload is encoded
as a string suitable for generating a QR code that the administrator can distribute.

Example usage:

    python -m scripts.provision_invite --uid exampleUser --claims '{"role": "admin"}' \
        --project my-firebase-project --service-account path/to/serviceAccount.json
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import secrets
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, Optional

try:
    import firebase_admin
    from firebase_admin import auth, credentials, firestore
except ImportError as exc:  # pragma: no cover - defensive import guard
    raise SystemExit(
        "firebase_admin is required. Install it with 'pip install firebase-admin'."
    ) from exc


@dataclass
class InvitePayload:
    """Represent the signed invite payload."""

    token: str
    expires_at: datetime
    nonce: str
    uid: str
    custom_claims: Dict[str, Any]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "token": self.token,
            "expires_at": self.expires_at.isoformat(),
            "nonce": self.nonce,
            "uid": self.uid,
            "custom_claims": self.custom_claims,
        }

    def encode_for_qr(self) -> str:
        """Encode the payload to a compact base64 string suitable for QR codes."""
        json_bytes = json.dumps(self.to_dict(), separators=(",", ":")).encode("utf-8")
        return base64.urlsafe_b64encode(json_bytes).decode("ascii")

    def hash(self) -> str:
        """Return a SHA-256 hash of the payload for storage and validation."""
        return hashlib.sha256(json.dumps(self.to_dict(), sort_keys=True).encode("utf-8")).hexdigest()


def initialize_firebase(service_account: Optional[str], project: Optional[str]) -> firebase_admin.App:
    """Initialize the Firebase Admin SDK using the provided credentials."""
    if firebase_admin._apps:  # pragma: no cover - avoid double init in tests
        return firebase_admin.get_app()

    if service_account:
        cred = credentials.Certificate(service_account)
        return firebase_admin.initialize_app(cred, {"projectId": project} if project else None)

    if project:
        return firebase_admin.initialize_app(options={"projectId": project})

    return firebase_admin.initialize_app()


def mint_custom_token(uid: str, claims: Dict[str, Any]) -> str:
    """Mint a Firebase custom token for the specified UID."""
    return auth.create_custom_token(uid, claims).decode("utf-8")


def store_invite(invite_id: str, payload: InvitePayload, include_payload: bool) -> None:
    """Persist the invite metadata in Firestore under pending_invites/{invite_id}."""
    client = firestore.client()
    doc_ref = client.collection("pending_invites").document(invite_id)
    doc_data: Dict[str, Any] = {
        "payload_hash": payload.hash(),
        "uid": payload.uid,
        "expires_at": payload.expires_at,
        "nonce": payload.nonce,
        "created_at": datetime.now(timezone.utc),
        "redeemed": False,
    }
    if include_payload:
        doc_data["payload"] = payload.to_dict()

    doc_ref.set(doc_data)


def parse_claims(raw_claims: Optional[str]) -> Dict[str, Any]:
    if not raw_claims:
        return {}
    try:
        claims = json.loads(raw_claims)
    except json.JSONDecodeError as exc:  # pragma: no cover - CLI validation
        raise SystemExit(f"Invalid JSON for --claims: {exc}") from exc
    if not isinstance(claims, dict):  # pragma: no cover - CLI validation
        raise SystemExit("--claims must decode to a JSON object")
    return claims


def build_payload(uid: str, token: str, claims: Dict[str, Any], ttl_minutes: int) -> InvitePayload:
    expires_at = datetime.now(timezone.utc) + timedelta(minutes=ttl_minutes)
    nonce = secrets.token_urlsafe(16)
    return InvitePayload(
        token=token,
        expires_at=expires_at,
        nonce=nonce,
        uid=uid,
        custom_claims=claims,
    )


def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--uid", required=True, help="User identifier for the custom token")
    parser.add_argument(
        "--claims",
        help="JSON string of custom claims to attach to the token (e.g., '{\"role\": \"admin\"}')",
    )
    parser.add_argument(
        "--ttl-minutes",
        type=int,
        default=10,
        help="Minutes until the invite expires (default: 10)",
    )
    parser.add_argument(
        "--invite-id",
        help="Optional invite document ID. Defaults to a generated secure random string.",
    )
    parser.add_argument(
        "--project",
        help="Firebase project ID. Required when not using a service account file.",
    )
    parser.add_argument(
        "--service-account",
        help="Path to a Firebase service account JSON file.",
    )
    parser.add_argument(
        "--include-payload",
        action="store_true",
        help="Store the full payload in Firestore (in addition to the hash).",
    )
    parser.add_argument(
        "--print-qr",
        action="store_true",
        help="Print an ASCII QR code representation if the 'qrcode' package is available.",
    )
    return parser.parse_args(argv)


def maybe_render_ascii_qr(data: str) -> Optional[str]:
    try:
        import qrcode
    except ImportError:  # pragma: no cover - optional dependency
        return None

    qr_obj = qrcode.QRCode(version=None, error_correction=qrcode.constants.ERROR_CORRECT_M)
    qr_obj.add_data(data)
    qr_obj.make(fit=True)
    matrix = qr_obj.get_matrix()
    lines = []
    for row in matrix:
        line = "".join("██" if cell else "  " for cell in row)
        lines.append(line)
    return "\n".join(lines)


def main(argv: Optional[list[str]] = None) -> int:
    args = parse_args(argv)
    claims = parse_claims(args.claims)
    invite_id = args.invite_id or secrets.token_urlsafe(8)

    initialize_firebase(args.service_account, args.project)

    token = mint_custom_token(args.uid, claims)
    payload = build_payload(args.uid, token, claims, args.ttl_minutes)

    store_invite(invite_id, payload, include_payload=args.include_payload)

    encoded_payload = payload.encode_for_qr()

    print("Invite successfully created!")
    print(f"Invite ID: {invite_id}")
    print(f"Expires at: {payload.expires_at.isoformat()}")
    print(f"QR payload: {encoded_payload}")

    if args.print_qr:
        ascii_qr = maybe_render_ascii_qr(encoded_payload)
        if ascii_qr:
            print("\nASCII QR code:\n")
            print(ascii_qr)
        else:
            print("\nInstall the 'qrcode' package to enable ASCII QR code output.")

    return 0


if __name__ == "__main__":  # pragma: no cover - CLI entry point
    sys.exit(main())
