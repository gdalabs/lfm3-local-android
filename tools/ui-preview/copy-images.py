#!/usr/bin/env python3
"""Copy the four English UI preview captures into the README image directory."""
from pathlib import Path
import shutil
here = Path(__file__).resolve().parent
root = here.parent.parent
source = here / "preview/src/test/snapshots/images"
target = root / "docs/screenshots"
target.mkdir(parents=True, exist_ok=True)
for name in ["chat-light", "chat-dark", "models", "settings"]:
    images = list(source.glob("*" + "-" + name + ".png"))
    if len(images) != 1:
        raise SystemExit(f"Expected one capture for {name}, got {len(images)}")
    shutil.copy2(images[0], target / (name + ".png"))
    print(target / (name + ".png"))
