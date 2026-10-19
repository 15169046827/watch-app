"""Fail closed on stale Edge dependencies or a mismatched portable Python ABI."""

import argparse
import importlib.metadata
from pathlib import Path
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--packages", required=True, type=Path)
    args = parser.parse_args()
    target = (args.packages / "site-packages").resolve(strict=True)
    if sys.version_info[:2] != (3, 11):
        raise RuntimeError("Edge portable dependencies require Python 3.11")
    sys.path.insert(0, str(target))
    installed = {
        dist.metadata["Name"].lower().replace("_", "-"): dist.version
        for dist in importlib.metadata.distributions(path=[str(target)])
    }
    for line in Path(__file__).with_name("requirements.txt").read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        name, version = line.split("==")
        if installed.get(name.lower().replace("_", "-")) != version:
            raise RuntimeError("Edge dependency baseline mismatch: " + name)
    import aiohttp.http_parser
    import multidict

    if (aiohttp.http_parser.HttpResponseParser.__module__ != "aiohttp._http_parser"
            or multidict.MultiDict.__module__ != "multidict._multidict"):
        raise RuntimeError("Edge compiled dependencies do not match the Python ABI")
    print("Edge dependency versions and compiled Python 3.11 ABI OK")


if __name__ == "__main__":
    main()
