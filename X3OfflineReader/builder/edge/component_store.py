"""Immutable online-client versions; a build pins one selection until it finishes."""
import json
import os
import re
import sys
from pathlib import Path

VERSION = re.compile(r"7\.[0-9]+\.[0-9]+")


def selected(root):
    try:
        version = json.loads((Path(root) / "active.json").read_text(encoding="utf-8"))["version"]
        if VERSION.fullmatch(version) and (Path(root) / version / "edge_tts" / "__init__.py").is_file():
            return version
    except (OSError, ValueError, KeyError, TypeError):
        pass
    return "bundled"


def activate_import():
    root = os.environ.get("X3_EDGE_COMPONENT_ROOT")
    version = os.environ.get("X3_EDGE_CLIENT", "bundled")
    if version != "bundled":
        if not root or not VERSION.fullmatch(version):
            raise RuntimeError("Invalid online-client selection")
        directory = Path(root) / version
        if not (directory / "edge_tts" / "__init__.py").is_file():
            raise RuntimeError("Selected online-client version is missing")
        sys.path.insert(0, str(directory))
