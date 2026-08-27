#!/usr/bin/env python3
"""Draft 2020-12 JSON Schema plus package security and cross-file checks."""

from __future__ import annotations

import json
import hashlib
import sys
import zipfile
import tempfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[2]
LOCAL_PACKAGES = ROOT / ".tools" / "schema-validator"
if LOCAL_PACKAGES.is_dir():
    sys.path.insert(0, str(LOCAL_PACKAGES))
try:
    from jsonschema import Draft202012Validator, ValidationError
except ImportError as error:
    raise SystemExit("Install specification/requirements.txt into .tools/schema-validator") from error
SCHEMAS = ROOT / "specification" / "schemas"
FIXTURE = ROOT / "samples" / "schema-fixtures" / "valid-v1"
INVALID_CASES = ROOT / "samples" / "schema-fixtures" / "invalid-v1" / "cases.json"


def load(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise AssertionError(f"Expected JSON object: {path}")
    return value


def schema(name: str) -> Draft202012Validator:
    return Draft202012Validator(load(SCHEMAS / f"{name}.schema.json"))


def safe_relative(path: str) -> None:
    candidate = PurePosixPath(path)
    assert not candidate.is_absolute(), path
    assert "\\" not in path and "\x00" not in path and "//" not in path, path
    assert all(part not in ("", ".", "..") for part in candidate.parts), path


def continuous_order(items: list[dict], label: str) -> None:
    assert [item["order"] for item in items] == list(range(len(items))), label


def validate_timeline(duration_ms: int, segments: list[dict], blocks: dict[str, dict]) -> None:
    prior_end = 0
    segment_ids: set[str] = set()
    for segment in segments:
        assert segment["segmentId"] not in segment_ids
        segment_ids.add(segment["segmentId"])
        assert prior_end <= segment["startMs"] < segment["endMs"] <= duration_ms
        prior_end = segment["endMs"]
        block_text = blocks[segment["blockId"]]["text"]
        assert 0 <= segment["startChar"] < segment["endChar"] <= len(block_text)
        assert block_text[segment["startChar"]:segment["endChar"]] == segment["text"]


def expect_rejected(label: str, operation) -> None:
    try:
        operation()
    except (AssertionError, KeyError, ValidationError):
        return
    raise AssertionError(f"Invalid fixture was accepted: {label}")


def validate_package(package: Path) -> None:
    with zipfile.ZipFile(package) as archive:
        infos = [item for item in archive.infolist() if not item.is_dir()]
        assert 0 < len(infos) <= 4096, "entry count"
        names: set[str] = set()
        total = 0
        for info in infos:
            safe_relative(info.filename)
            assert info.filename not in names, f"duplicate entry: {info.filename}"
            names.add(info.filename)
            assert 0 <= info.file_size <= 512 * 1024 * 1024, f"entry too large: {info.filename}"
            total += info.file_size
            assert total <= 4 * 1024 * 1024 * 1024, "expanded package too large"
            if info.file_size > 1024 * 1024:
                assert info.compress_size > 0 and info.file_size / info.compress_size <= 500, f"compression ratio: {info.filename}"

        assert {"manifest.json", "checksums.json"}.issubset(names)
        manifest = json.loads(archive.read("manifest.json"))
        checksums = json.loads(archive.read("checksums.json"))
        referenced={"manifest.json","checksums.json"}
        schema("manifest").validate(manifest)
        schema("checksums").validate(checksums)
        assert manifest["format"] == "opbook" and manifest["formatVersion"] == "1.0"
        if "coverPath" in manifest["book"]:
            cover=manifest["book"]["coverPath"]
            assert cover=="cover.webp" and cover in names
            header=archive.read(cover)[0:12]
            assert len(header)==12 and header[0:4]==b"RIFF" and header[8:12]==b"WEBP"
            referenced.add(cover)
        assert checksums["formatVersion"] == "1.0" and checksums["algorithm"] == "SHA-256"
        expected_files = names - {"checksums.json"}
        assert set(checksums["files"]) == expected_files, "checksum coverage"
        for name, expected in checksums["files"].items():
            assert hashlib.sha256(archive.read(name)).hexdigest() == expected, f"checksum mismatch: {name}"

        continuous_order(manifest["volumes"], "volume order")
        ids: set[str] = {manifest["book"]["bookId"]}
        for volume in manifest["volumes"]:
            assert volume["volumeId"] not in ids; ids.add(volume["volumeId"])
            continuous_order(volume["chapters"], "chapter order")
            for chapter in volume["chapters"]:
                assert chapter["chapterId"] not in ids; ids.add(chapter["chapterId"])
                continuous_order(chapter["tracks"], "track order")
                safe_relative(chapter["contentPath"])
                content = json.loads(archive.read(chapter["contentPath"]))
                referenced.add(chapter["contentPath"])
                schema("content").validate(content)
                assert content["chapterId"] == chapter["chapterId"]
                continuous_order(content["blocks"], "block order")
                blocks = {block["blockId"]: block for block in content["blocks"]}
                assert len(blocks) == len(content["blocks"])
                for track in chapter["tracks"]:
                    assert track["trackId"] not in ids; ids.add(track["trackId"])
                    assert track["mimeType"] == "audio/mp4"
                    safe_relative(track["audioPath"]); safe_relative(track["timelinePath"])
                    timeline = json.loads(archive.read(track["timelinePath"]))
                    referenced.add(track["timelinePath"]);referenced.add(track["audioPath"])
                    schema("timeline").validate(timeline)
                    assert timeline["trackId"] == track["trackId"] and timeline["chapterId"] == chapter["chapterId"]
                    assert timeline["durationMs"] == track["durationMs"]
                    validate_timeline(timeline["durationMs"], timeline["segments"], blocks)
                    header = archive.read(track["audioPath"])[0:16]
                    assert len(header) >= 12 and header[4:8] == b"ftyp", f"not M4A/MP4: {track['audioPath']}"
        managed={name for name in names if name.startswith(("content/","timeline/","audio/"))}
        assert managed.issubset(referenced),"unreferenced managed resource"


def package_negative_tests(base: Path) -> int:
    with zipfile.ZipFile(base) as archive:
        original={info.filename:archive.read(info.filename) for info in archive.infolist() if not info.is_dir()}
    def write_case(name: str,files: dict[str,bytes]) -> Path:
        path=temp_root / name
        with zipfile.ZipFile(path,"w",zipfile.ZIP_DEFLATED) as archive:
            for entry,data in sorted(files.items()):archive.writestr(entry,data)
        return path
    def checksummed(files: dict[str,bytes]) -> dict[str,bytes]:
        result=dict(files);doc=json.loads(result["checksums.json"]);doc["files"]={name:hashlib.sha256(data).hexdigest() for name,data in result.items() if name!="checksums.json"};result["checksums.json"]=json.dumps(doc,separators=(",",":")).encode();return result
    with tempfile.TemporaryDirectory(prefix="opbook-negative-") as directory:
        temp_root=Path(directory)
        audio=next(name for name in original if name.startswith("audio/"));changed=dict(original);data=bytearray(changed[audio]);data[-1]^=1;changed[audio]=bytes(data)
        expect_rejected("package-checksum",lambda:validate_package(write_case("checksum.opbook",changed)))
        unsafe=dict(original);unsafe["../escape"]=b"x";expect_rejected("package-path",lambda:validate_package(write_case("path.opbook",unsafe)))
        timeline_name=next(name for name in original if name.startswith("timeline/"));timeline=json.loads(original[timeline_name]);timeline["segments"][0]["endMs"]=timeline["durationMs"]+1;changed=dict(original);changed[timeline_name]=json.dumps(timeline,separators=(",",":"),ensure_ascii=False).encode();changed=checksummed(changed);expect_rejected("package-timeline",lambda:validate_package(write_case("timeline.opbook",changed)))
        bomb=dict(original);bomb["bomb.bin"]=b"0"*(2*1024*1024);expect_rejected("package-compression",lambda:validate_package(write_case("bomb.opbook",bomb)))
        orphan=dict(original);orphan["content/orphan.json"]=b"{}";orphan=checksummed(orphan);expect_rejected("package-unreferenced",lambda:validate_package(write_case("orphan.opbook",orphan)))
    return 5


def main() -> None:
    for path in sorted(SCHEMAS.glob("*.schema.json")):
        schema_document = load(path)
        assert schema_document["$schema"] == "https://json-schema.org/draft/2020-12/schema"
        assert schema_document["additionalProperties"] is False
        Draft202012Validator.check_schema(schema_document)

    manifest = load(FIXTURE / "manifest.json")
    schema("manifest").validate(manifest)
    assert manifest["format"] == "opbook" and manifest["formatVersion"] == "1.0"
    assert manifest["checksumsPath"] == "checksums.json"
    continuous_order(manifest["volumes"], "volume order")

    seen_ids: set[str] = {manifest["book"]["bookId"]}
    for volume in manifest["volumes"]:
        assert volume["volumeId"] not in seen_ids
        seen_ids.add(volume["volumeId"])
        continuous_order(volume["chapters"], f"chapters in {volume['volumeId']}")
        for chapter in volume["chapters"]:
            assert chapter["chapterId"] not in seen_ids
            seen_ids.add(chapter["chapterId"])
            continuous_order(chapter["tracks"], f"tracks in {chapter['chapterId']}")
            safe_relative(chapter["contentPath"])
            content = load(FIXTURE / chapter["contentPath"])
            schema("content").validate(content)
            assert content["formatVersion"] == "1.0"
            assert content["chapterId"] == chapter["chapterId"]
            continuous_order(content["blocks"], f"blocks in {chapter['chapterId']}")
            blocks = {block["blockId"]: block for block in content["blocks"]}
            assert len(blocks) == len(content["blocks"])

            for track in chapter["tracks"]:
                assert track["trackId"] not in seen_ids
                seen_ids.add(track["trackId"])
                safe_relative(track["audioPath"])
                safe_relative(track["timelinePath"])
                assert track["mimeType"] == "audio/mp4"
                timeline = load(FIXTURE / track["timelinePath"])
                schema("timeline").validate(timeline)
                assert timeline["formatVersion"] == "1.0"
                assert timeline["trackId"] == track["trackId"]
                assert timeline["chapterId"] == chapter["chapterId"]
                assert timeline["durationMs"] == track["durationMs"]

                validate_timeline(timeline["durationMs"], timeline["segments"], blocks)

    unexpected=dict(manifest);unexpected["unexpected"]=True
    expect_rejected("schema-additional-property",lambda: schema("manifest").validate(unexpected))

    invalid = load(INVALID_CASES)
    for case in invalid["unsafePaths"]:
        expect_rejected(case["id"], lambda value=case["value"]: safe_relative(value))
    for case in invalid["invalidTimelines"]:
        blocks = {"block": {"text": case["blockText"]}}
        expect_rejected(
            case["id"],
            lambda current=case, current_blocks=blocks: validate_timeline(
                current["durationMs"], current["segments"], current_blocks
            ),
        )

    count = len(invalid["unsafePaths"]) + len(invalid["invalidTimelines"]) + 1
    packages = [Path(value) for value in sys.argv[1:]]
    for package in packages:
        validate_package(package.resolve())
    package_negatives=package_negative_tests(packages[0].resolve()) if packages else 0
    suffix = f"; {len(packages)} package(s) pass; {package_negatives} package attacks rejected" if packages else ""
    print(f"OK: 4 schemas parse; valid-v1 passes; {count} invalid cases rejected{suffix}.")


if __name__ == "__main__":
    main()
