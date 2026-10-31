#!/usr/bin/env python3
"""Provision the pinned model on a build computer; the Android app has no network permission."""
import argparse
import hashlib
import json
import pathlib
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("destination", type=pathlib.Path)
parser.add_argument("--format", choices=["q4", "q6"], default="q4")
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
name = "lfm3-uncensored-q6.json" if args.format == "q6" else "lfm3-uncensored.json"
pin = json.loads((root / "model-manager/manifests" / name).read_text())
url = ("https://huggingface.co/AtomicChat/LFM3-1.7B-Uncensored-GGUF/resolve/" +
       pin["version"] + "/LFM3-1.7B-Uncensored-" + pin["gguf_format"] + ".gguf")
if args.destination.exists():
    raise SystemExit("Destination already exists; refusing to overwrite it.")
digest = hashlib.sha256()
count = 0
with urllib.request.urlopen(url) as response, args.destination.open("xb") as target:
    while chunk := response.read(1024 * 1024):
        count += len(chunk)
        if count > pin["size_bytes"]:
            raise SystemExit("Model exceeds its pinned size; do not import this partial file.")
        digest.update(chunk)
        target.write(chunk)
assert count == pin["size_bytes"] and digest.hexdigest() == pin["sha256"], "Model integrity check failed"
print("Pinned model size and SHA-256 verified")
