"""Fail closed before importing Melo or publishing a stale Python runtime."""

import importlib.metadata
import sys


def validate_runtime(version_reader=importlib.metadata.version, python_version=None):
    """Require the tested security baseline, retaining CPU wheel local versions."""
    python_version = python_version or sys.version_info[:2]
    if python_version < (3, 11):
        raise RuntimeError(
            "Melo requires Python 3.11+; create a separate upgraded environment"
        )
    expected = {"transformers": "5.10.1", "torch": "2.13.0"}
    for package, required in expected.items():
        actual = version_reader(package)
        if actual not in {required, required + "+cpu"}:
            raise RuntimeError("Melo requires tested " + package + "==" + required)
    return expected


if __name__ == "__main__":
    print("Melo runtime security baseline OK: " + str(validate_runtime()))
