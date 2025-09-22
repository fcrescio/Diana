#!/usr/bin/env python3
"""Upload LLM resources to Firestore.

This script synchronizes the local resources stored in ``app/src/main/resources/llm``
with a Firestore collection so that runtime overrides can be distributed
without shipping a new application build.
"""

from __future__ import annotations

import argparse
import base64
import logging
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import firebase_admin
from firebase_admin import credentials, firestore


DEFAULT_SOURCE = Path("app/src/main/resources/llm")
DEFAULT_COLLECTION = "resources"


@dataclass(frozen=True)
class RemoteResource:
    reference: firestore.DocumentReference
    content: str | None
    document_id: str


def parse_args(argv: Iterable[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Upload LLM resources to Firestore")
    parser.add_argument(
        "--source",
        type=Path,
        default=DEFAULT_SOURCE,
        help="Directory containing resource files (default: %(default)s)",
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
        help="Target Firestore collection name (default: %(default)s)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Preview the changes without writing to Firestore",
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


def load_local_resources(source_dir: Path) -> dict[str, str]:
    if not source_dir.exists() or not source_dir.is_dir():
        raise FileNotFoundError(f"Source directory not found: {source_dir}")

    resources: dict[str, str] = {}
    for file_path in sorted(source_dir.rglob("*")):
        if not file_path.is_file():
            continue
        try:
            relative = file_path.relative_to(source_dir).as_posix()
        except ValueError:
            logging.debug("Unable to compute relative path for %s", file_path)
            continue
        try:
            normalized = normalize_path(relative)
        except ValueError as exc:
            logging.warning("Skipping %s: %s", file_path, exc)
            continue
        try:
            content = file_path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            logging.warning("Skipping %s: not valid UTF-8", file_path)
            continue
        if normalized in resources:
            logging.warning(
                "Duplicate resource path %s; overwriting previous entry with %s",
                normalized,
                file_path,
            )
        resources[normalized] = content
    return resources


def init_firestore(service_account: Path, project_id: str | None) -> firestore.Client:
    if not service_account.is_file():
        raise FileNotFoundError(f"Service account key not found: {service_account}")

    cred = credentials.Certificate(service_account)
    if project_id:
        firebase_admin.initialize_app(cred, {"projectId": project_id})
    else:
        firebase_admin.initialize_app(cred)
    return firestore.client()


def fetch_remote_resources(collection_ref: firestore.CollectionReference) -> dict[str, RemoteResource]:
    remote: dict[str, RemoteResource] = {}
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
        if content is None:
            logging.warning(
                "Remote document %s (%s) is missing content; treating as empty",
                doc.id,
                normalized,
            )
        remote[normalized] = RemoteResource(reference=doc.reference, content=content, document_id=doc.id)
    return remote


def generate_document_id(normalized_path: str) -> str:
    encoded = base64.urlsafe_b64encode(normalized_path.encode("utf-8")).decode("ascii")
    return encoded.rstrip("=")


def sync_resources(
    firestore_client: firestore.Client,
    collection_ref: firestore.CollectionReference,
    local_resources: dict[str, str],
    remote_resources: dict[str, RemoteResource],
    dry_run: bool,
) -> None:
    batch = firestore_client.batch()

    to_create: list[tuple[str, firestore.DocumentReference, dict[str, str]]] = []
    to_update: list[tuple[str, RemoteResource, dict[str, str]]] = []
    to_delete: list[tuple[str, RemoteResource]] = []

    processed: set[str] = set()

    for normalized, content in sorted(local_resources.items()):
        processed.add(normalized)
        remote_entry = remote_resources.get(normalized)
        data = {"filename": normalized, "content": content}
        if remote_entry is None:
            doc_id = generate_document_id(normalized)
            doc_ref = collection_ref.document(doc_id)
            to_create.append((normalized, doc_ref, data))
        else:
            if remote_entry.content == content:
                logging.debug("No changes for %s", normalized)
                continue
            to_update.append((normalized, remote_entry, data))

    for normalized, remote_entry in sorted(remote_resources.items()):
        if normalized not in processed:
            to_delete.append((normalized, remote_entry))

    if not any((to_create, to_update, to_delete)):
        logging.info("No changes detected; Firestore is up to date")
        return

    logging.info(
        "Planned changes - create: %d, update: %d, delete: %d",
        len(to_create),
        len(to_update),
        len(to_delete),
    )

    for normalized, doc_ref, data in to_create:
        action = "Would create" if dry_run else "Creating"
        logging.info("%s document %s for %s", action, doc_ref.id, normalized)
        if not dry_run:
            batch.set(doc_ref, data)

    for normalized, remote_entry, data in to_update:
        action = "Would update" if dry_run else "Updating"
        logging.info("%s document %s for %s", action, remote_entry.document_id, normalized)
        if not dry_run:
            batch.set(remote_entry.reference, data)

    for normalized, remote_entry in to_delete:
        action = "Would delete" if dry_run else "Deleting"
        logging.info("%s document %s for %s", action, remote_entry.document_id, normalized)
        if not dry_run:
            batch.delete(remote_entry.reference)

    if dry_run:
        logging.info(
            "Dry run complete: %d to create, %d to update, %d to delete",
            len(to_create),
            len(to_update),
            len(to_delete),
        )
        return

    result = batch.commit()
    logging.info(
        "Synchronization complete: %d created, %d updated, %d deleted",
        len(to_create),
        len(to_update),
        len(to_delete),
    )
    logging.debug("Batch commit result: %s", result)


def main(argv: Iterable[str]) -> int:
    configure_logging()
    args = parse_args(argv)

    try:
        local_resources = load_local_resources(args.source)
    except FileNotFoundError as exc:
        logging.error(str(exc))
        return 1

    logging.info("Loaded %d local resources from %s", len(local_resources), args.source)

    try:
        firestore_client = init_firestore(args.service_account, args.project)
    except FileNotFoundError as exc:
        logging.error(str(exc))
        return 1
    except Exception as exc:  # pragma: no cover - firebase_admin raises various errors
        logging.error("Failed to initialize Firebase Admin SDK: %s", exc)
        return 1

    collection_ref = firestore_client.collection(args.collection)
    remote_resources = fetch_remote_resources(collection_ref)
    logging.info("Fetched %d remote resources from collection '%s'", len(remote_resources), args.collection)

    try:
        sync_resources(firestore_client, collection_ref, local_resources, remote_resources, args.dry_run)
    except Exception as exc:  # pragma: no cover - propagate unexpected errors gracefully
        logging.error("Failed to synchronize resources: %s", exc)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
