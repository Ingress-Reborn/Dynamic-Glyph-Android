#!/usr/bin/env python3
"""Fetches the third-party reference tables into local-data/.

The two CSVs are NOT redistributed with this project: the upstream publishes no
licence. They are fetched from a pinned revision and verified against known
hashes, and they land in `local-data/`, which is gitignored.

Usage:
    python tools/fetch_reference_data.py

Then point the app at the two files once; it copies them into its own storage.
"""

import hashlib
import pathlib
import shutil
import subprocess
import sys
import tempfile

UPSTREAM = "https://raw.githubusercontent.com/chibatching/glyph-predictor-data"
REPO = "https://github.com/chibatching/glyph-predictor-data"
REVISION = "109f6c32d24c9f219e946a1423642390db6ce86b"

EXPECTED = {
    "glyph_line.csv": "72c3e0f1b93992cf725bf2ce8adfdb2df4daddf6b5f8a6c8f7e8629e6934b6e7",
    "glyph.csv": "b1eaac450aa60643e25c17ae5c12ba8803dd0c50d60823dda5f6768ba9e2c5bf",
}

DEST = pathlib.Path(__file__).resolve().parent.parent / "local-data"


def download_http(name: str) -> bytes:
    import urllib.request

    url = f"{UPSTREAM}/{REVISION}/{name}"
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()


def download_git(name: str) -> bytes:
    """Fallback for networks where the HTTPS client cannot complete a handshake.

    Reads the blob out of git rather than checking out, so the bytes are the ones
    committed upstream and no line-ending conversion is applied on the way.
    """
    work = tempfile.mkdtemp(prefix="glyph-ref-")
    try:
        repo = pathlib.Path(work, "data")
        subprocess.run(["git", "init", "--quiet", str(repo)], check=True)
        subprocess.run(
            ["git", "-C", str(repo), "fetch", "--depth", "1", REPO, REVISION],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        result = subprocess.run(
            ["git", "-C", str(repo), "cat-file", "blob", f"FETCH_HEAD:{name}"],
            check=True,
            capture_output=True,
        )
        return result.stdout
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main() -> int:
    DEST.mkdir(parents=True, exist_ok=True)
    failures = 0

    for name, expected in EXPECTED.items():
        print(f"fetching {name} …", flush=True)
        payload = None
        for attempt, fetch in (("https", download_http), ("git", download_git)):
            try:
                payload = fetch(name)
                break
            except Exception as error:  # noqa: BLE001 - try the next transport
                print(f"  {attempt} transport failed: {error}")

        if payload is None:
            failures += 1
            continue

        digest = hashlib.sha256(payload).hexdigest()
        if digest != expected:
            # A line-ending conversion in transit is the usual cause. The
            # parsers split on CRLF, LF and CR alike, so this is a warning about
            # provenance, not a reason to discard usable data.
            print(f"  hash mismatch\n    expected {expected}\n    got      {digest}")
            failures += 1
            continue

        (DEST / name).write_bytes(payload)
        print(f"  wrote local-data/{name} ({len(payload)} bytes)")

    print()
    if failures:
        print(f"{failures} file(s) could not be verified. Read the upstream licence position")
        print("yourself before using whatever did arrive.")
        return 1

    print("Both files verified against the pinned revision.")
    print("In the app: load each file once from local-data/.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
