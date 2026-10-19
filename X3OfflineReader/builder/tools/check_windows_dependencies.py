"""Check the resolved supported-target graph, not the cross-platform lock inventory."""

import argparse
from pathlib import Path
import subprocess


def check_graph(lines):
    packages = {line.split()[0] for line in lines if line.strip()}
    if "tingdu-workshop" not in packages:
        raise ValueError("Cargo did not report the Builder root package")
    forbidden = packages.intersection({"glib", "gtk", "webkit2gtk"})
    if forbidden:
        raise ValueError(
            "Unsupported GTK runtime in Windows graph: " + ", ".join(sorted(forbidden))
        )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    manifest = Path(__file__).resolve().parents[1] / "ui" / "src-tauri" / "Cargo.toml"
    command = [
        "cargo",
        "tree",
        "--manifest-path",
        str(manifest),
        "--locked",
        "--target",
        "x86_64-pc-windows-msvc",
        "--prefix",
        "none",
        "--format",
        "{p}",
    ]
    if args.offline:
        command.append("--offline")
    result = subprocess.run(
        command, check=True, capture_output=True, text=True, encoding="utf-8"
    )
    check_graph(result.stdout.splitlines())
    print("Windows dependency graph OK: no glib/GTK/WebKitGTK")


if __name__ == "__main__":
    main()
