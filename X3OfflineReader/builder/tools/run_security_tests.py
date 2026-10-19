"""Stdlib-only dependency policy tests for Gradle/CI; never load user models."""

from pathlib import Path
import subprocess
import sys


def main():
    builder = Path(__file__).resolve().parents[1]
    for directory, pattern in [
        ("melo", "test_*.py"),
        ("tools", "test_windows_dependencies.py"),
    ]:
        subprocess.run(
            [
                sys.executable,
                "-B",
                "-m",
                "unittest",
                "discover",
                "-s",
                str(builder / directory),
                "-p",
                pattern,
            ],
            check=True,
        )


if __name__ == "__main__":
    main()
