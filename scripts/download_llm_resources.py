#!/usr/bin/env python3
"""Download LLM resources from Firestore into a timestamped archive."""

from __future__ import annotations

import argparse
import json
import logging
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

import firebase_admin
from firebase_admin import credentials, firestore

DEFAULT_COLLECTION = "resources"
DEFAULT_DESTINATION = Path("archives/llm")


@dataclass(frozen=True)
class RemoteResource:
    path: str
    content: str | None
    document_id: str


def parse_args(argv: Iterable[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Download LLM resources from Firestore")
    parser.add_argument(
        "--destination",
        type=Path,
        default=DEFAULT_DESTINATION,
        help="Directory where the timestamped archive will be created (default: %(default)s)",
    )
    parser.add_argument(
        "--service-account",
        "--credentials",
        dest="service_account",
        type=Path,
        required=True,
        help="Path to the Firebase service account JSON key",
    )
    parser.add_argument(
        "--project",
        help="Optional Firebase project ID override",
    )
    parser.add_argument(
        "--collection",
        default=DEFAULT_COLLECTION,
        help="Firestore collection name to download (default: %(default)s)",
    )
    parser.add_argument(
        "--timestamp",
        help="Optional timestamp for the archive directory; defaults to current UTC time",
    )
    return parser.parse_args(list(argv))


def configure_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(levelname)s: %(message)s",
    )


def normalize_path(path: str) -> str:
    trimmed = path.strip().lstrip("/")
    if not trimmed:
        raise ValueError("Empty path")
    if ".." in trimmed:
        raise ValueError(f"Invalid path: {path}")
    normalized = trimmed.replace("\\", "/")
    if not normalized.startswith("llm/"):
        normalized = f"llm/{normalized}"
    return normalized


def init_firestore(service_account: Path, project_id: str | None) -> firestore.Client:
    if not service_account.is_file():
        raise FileNotFoundError(f"Service account key not found: {service_account}")

    cred = credentials.Certificate(service_account)
    if not firebase_admin._apps:
        if project_id:
            firebase_admin.initialize_app(cred, {"projectId": project_id})
        else:
            firebase_admin.initialize_app(cred)
    return firestore.client()


def fetch_remote_resources(collection_ref: firestore.CollectionReference) -> list[RemoteResource]:
    resources: list[RemoteResource] = []
    for doc in collection_ref.stream():
        data = doc.to_dict() or {}
        filename = (data.get("filename") or "").strip()
        content = data.get("content")
        if not filename:
            logging.warning("Skipping remote document %s due to missing filename", doc.id)
            continue
        try:
            normalized = normalize_path(filename)
        except ValueError as exc:
            logging.warning("Skipping remote document %s: %s", doc.id, exc)
            continue
        if content is not None and not isinstance(content, str):
            logging.warning(
                "Skipping remote document %s (%s): unexpected content type %s",
                doc.id,
                normalized,
                type(content).__name__,
            )
            continue
        resources.append(RemoteResource(path=normalized, content=content, document_id=doc.id))
    return resources


def resolve_timestamp(provided: str | None) -> str:
    if provided:
        return provided
    now = datetime.now(timezone.utc)
    return now.strftime("%Y%m%dT%H%M%SZ")


def ensure_directory(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)


def write_resources(destination: Path, resources: list[RemoteResource]) -> None:
    for resource in resources:
        target_path = destination / resource.path
        ensure_directory(target_path.parent)
        text = resource.content or ""
        target_path.write_text(text, encoding="utf-8")
        logging.info("Saved %s", target_path)


def write_manifest(destination: Path, resources: list[RemoteResource], metadata: dict[str, str]) -> None:
    manifest_path = destination / "manifest.json"
    payload = {
        "metadata": metadata,
        "documents": [
            {"path": resource.path, "document_id": resource.document_id}
            for resource in resources
        ],
    }
    manifest_path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
    logging.info("Wrote manifest to %s", manifest_path)


def main(argv: Iterable[str]) -> int:
    configure_logging()
    args = parse_args(argv)

    try:
        firestore_client = init_firestore(args.service_account, args.project)
    except FileNotFoundError as exc:
        logging.error(str(exc))
        return 1
    except Exception as exc:  # pragma: no cover - firebase_admin raises various errors
        logging.error("Failed to initialize Firebase Admin SDK: %s", exc)
        return 1

    collection_ref = firestore_client.collection(args.collection)
    resources = fetch_remote_resources(collection_ref)
    logging.info(
        "Fetched %d remote resources from collection '%s'", len(resources), args.collection
    )

    if not resources:
        logging.warning("No resources found; nothing to archive")

    timestamp = resolve_timestamp(args.timestamp)
    archive_root = args.destination / timestamp
    ensure_directory(archive_root)

    try:
        write_resources(archive_root, resources)
    except OSError as exc:
        logging.error("Failed to write resources: %s", exc)
        return 1

    metadata = {
        "collection": args.collection,
        "timestamp": timestamp,
        "destination": str(archive_root.resolve()),
    }
    if args.project:
        metadata["project"] = args.project

    try:
        write_manifest(archive_root, resources, metadata)
    except OSError as exc:
        logging.error("Failed to write manifest: %s", exc)
        return 1

    logging.info("Archive created at %s", archive_root)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
