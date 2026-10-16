#!/usr/bin/env python3
"""Reconstruct a source-bound Git patch; never edits the checkout or any Git ref."""
import argparse
import base64
import bz2
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import tempfile

BASE = "406ee89aa7183c4e50c7b91c0006bdf9718911bd"
BASE_TREE = "0e8194419722ab30f9a673110468625fbe3fe6e6"
TARGET_TREE = "7bf9090c040ddf9cdd5e6a6f2d909c701795e531"
PAYLOAD_SHA256 = "3eb8c6c451873a3b364d1733d9e3119db3a30be8ec8e0c0c0e1b401fd8f7444f"
FILE_COUNT = 266


def git(repo, *args, data=None, env=None):
    return subprocess.run(["git", "-C", str(repo), *args], input=data,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          env=env, check=True).stdout


def payload(directory):
    encoded = b"".join((directory / f"part-{i:02}.b64").read_bytes().strip()
                       for i in range(1, 7))
    if len(encoded) != 67540:
        raise ValueError("Unexpected transfer size")
    decoder = bz2.BZ2Decompressor()
    raw = decoder.decompress(base64.b64decode(encoded, validate=True), max_length=2_000_000)
    if not decoder.eof or decoder.unused_data or hashlib.sha256(raw).hexdigest() != PAYLOAD_SHA256:
        raise ValueError("Source payload digest/stream mismatch")
    records = json.loads(raw)
    if not isinstance(records, list) or len(records) != FILE_COUNT:
        raise ValueError("Source file count mismatch")
    return records


def reconstruct(old, operations):
    last_end = 0
    for offset, removed, inserted in operations:
        if type(offset) is not int or type(removed) is not int or not isinstance(inserted, str):
            raise ValueError("Malformed edit")
        if offset < last_end or removed < 0 or offset + removed > len(old):
            raise ValueError("Overlapping or out-of-range edit")
        last_end = offset + removed
    new = old
    for offset, removed, inserted in reversed(operations):
        new = new[:offset] + inserted + new[offset + removed:]
    return new.encode("utf-8")


def restore(repo, output, base=BASE, directory=None):
    directory = directory or Path(__file__).resolve().parent
    # A local archival commit may have a different ID; only an identical complete
    # base tree is accepted. No synthetic local history is published upstream.
    if git(repo, "rev-parse", f"{base}^{{tree}}").decode().strip() != BASE_TREE:
        raise ValueError("Unexpected base source tree")
    files, seen = [], set()
    for path, before, after, operations in payload(directory):
        p = PurePosixPath(path)
        if (path in seen or p.is_absolute() or ".." in p.parts or ".git" in p.parts
                or str(p) != path or any(c in path for c in "\n\r\0\\:")):
            raise ValueError("Invalid source path")
        seen.add(path)
        entry = git(repo, "ls-tree", base, "--", path).decode().strip()
        if entry:
            if not entry.startswith("100644 blob "):
                raise ValueError("Unexpected source file mode")
            old = git(repo, "show", f"{base}:{path}")
        else:
            old = b""
        if hashlib.sha256(old).hexdigest() != before:
            raise ValueError(f"Base digest mismatch: {path}")
        new = reconstruct(old.decode("utf-8"), operations)
        if hashlib.sha256(new).hexdigest() != after:
            raise ValueError(f"Target digest mismatch: {path}")
        files.append((path, new))
    with tempfile.TemporaryDirectory(prefix="taxonomy-delta-") as scratch:
        env = dict(os.environ, GIT_INDEX_FILE=str(Path(scratch) / "index"))
        git(repo, "read-tree", base, env=env)
        for path, new in files:
            sha = git(repo, "hash-object", "-w", "--stdin", data=new).decode().strip()
            git(repo, "update-index", "--add", "--cacheinfo", "100644", sha, path, env=env)
        tree = git(repo, "write-tree", env=env).decode().strip()
        if tree != TARGET_TREE:
            raise ValueError(f"Complete target source tree mismatch: {tree}")
        patch = git(repo, "diff", "--binary", "--full-index", base, tree)
    output = output.resolve()
    # Refuse overwrites rather than replacing a user's existing patch.
    with output.open("xb") as stream:
        stream.write(patch)
    print(f"Verified {len(files)} source files; tree {tree}; patch {output}")
    print("Checkout, normal index and branch refs are unchanged.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--base", default=BASE)
    options = parser.parse_args()
    restore(options.repo.resolve(), options.output, options.base)


if __name__ == "__main__":
    main()
