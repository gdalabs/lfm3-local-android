#!/usr/bin/env python3
"""Fetch the exact audited llama.cpp source archive without modifying existing trees."""
import hashlib
import io
import json
import pathlib
import tarfile
import urllib.request

root = pathlib.Path(__file__).resolve().parents[1]
pin = json.loads((root / "dependencies.json").read_text())["llama_cpp"]
destination = root / "third_party/llama.cpp"
if destination.exists():
    raise SystemExit("Source already exists; verify its pin and local changes before replacing it.")
data = urllib.request.urlopen(pin["url"] + "/archive/" + pin["commit"] + ".tar.gz").read()
assert hashlib.sha256(data).hexdigest() == pin["archive_sha256"], "Archive checksum mismatch"
destination.parent.mkdir(exist_ok=True)
with tarfile.open(fileobj=io.BytesIO(data)) as archive:
    archive.extractall(destination.parent, filter="data")
(destination.parent / ("llama.cpp-" + pin["commit"])).rename(destination)
print("Pinned runtime source ready")
