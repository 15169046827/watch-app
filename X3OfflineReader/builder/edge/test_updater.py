"""Offline tests: no personal task data, no real network or TTS requests."""
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import updater
from component_store import selected


def wheel(extra=None, requires="", python=">=3.7"):
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as archive:
        archive.writestr("edge_tts/__init__.py", "__version__='7.3.2'")
        archive.writestr("edge_playback/__init__.py", "")
        archive.writestr("edge_tts-7.3.2.dist-info/METADATA",
                         "Name: edge-tts\nVersion: 7.3.2\nRequires-Python: " + python + "\n" + requires)
        if extra:
            info = zipfile.ZipInfo()
            info.filename = extra[0]
            archive.writestr(info, extra[1])
    data.seek(0)
    return data


def metadata():
    return {"info": {"version": "7.3.2"}, "urls": [{"packagetype": "bdist_wheel", "yanked": False,
            "filename": "edge_tts-7.3.2-py3-none-any.whl", "url": "https://files.pythonhosted.org/client",
            "digests": {"sha256": "0" * 64}}]}


class UpdaterTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_extract_only_client_and_metadata(self):
        updater.extract(wheel(), self.root, "7.3.2")
        self.assertTrue((self.root / "edge_tts/__init__.py").is_file())
        self.assertFalse((self.root / "edge_playback").exists())

    def test_traversal_other_roots_pth_and_duplicates_rejected(self):
        for path in ("edge_tts/../evil.py", "/evil.py", "other/code.py", "edge_tts/x.pth",
                     "edge_tts\\code.py", "edge_tts/__INIT__.py", "edge_tts/.. /evil.py",
                     "edge_tts/code.py.", "edge_tts/NUL.py", "edge_tts/x\x00.py"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                updater.extract(wheel((path, "bad")), self.root, "7.3.2")

    def test_python_and_dependencies_must_be_compatible(self):
        for kwargs in ({"python": ">=99"}, {"requires": "Requires-Dist: missing-tingdu-package>=1\n"},
                       {"requires": "Requires-Dist: aiohttp>=99\n"},
                       {"requires": "Requires-Dist: aiohttp @ https://example.org/a.whl\n"}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                updater.extract(wheel(**kwargs), self.root, "7.3.2")

    def test_prerelease_major_change_yanked_and_missing_wheel_rejected(self):
        for version in ("8.0.0", "7.3.2rc1", "7.2.8", "../7.3.2"):
            value = metadata()
            value["info"]["version"] = version
            with self.subTest(version=version), self.assertRaises(ValueError):
                updater.candidate_release(value)
        value = metadata()
        value["urls"][0]["yanked"] = True
        with self.assertRaises(ValueError):
            updater.candidate_release(value)

    def test_only_https_official_hosts(self):
        for url in ("http://pypi.org/a", "https://evil.example/a", "https://pypi.org:444/a"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                updater.fetch(url, "pypi.org", 100)

    def test_hash_failure_never_activates(self):
        with patch.object(updater, "fetch", return_value=b"bad"), self.assertRaises(ValueError):
            updater.install(self.root, self.root, metadata())
        self.assertEqual(selected(self.root), "bundled")

    def test_probe_failure_preserves_previous_pointer(self):
        self.prepare_old()
        data = wheel().getvalue()
        value = metadata()
        value["urls"][0]["digests"]["sha256"] = updater.hashlib.sha256(data).hexdigest()
        with patch.object(updater, "fetch", return_value=data), patch.object(
                updater, "probe", side_effect=ValueError("probe failed")), self.assertRaises(ValueError):
            updater.install(self.root, self.root, value)
        self.assertEqual(selected(self.root), "7.3.1")

    def prepare_old(self):
        (self.root / "7.3.1/edge_tts").mkdir(parents=True)
        (self.root / "7.3.1/edge_tts/__init__.py").write_text("old")
        updater.atomic_json(self.root / "active.json", {"version": "7.3.1"})

    def test_verified_update_keeps_previous_version(self):
        self.prepare_old()
        data = wheel().getvalue()
        value = metadata()
        value["urls"][0]["digests"]["sha256"] = updater.hashlib.sha256(data).hexdigest()
        with patch.object(updater, "fetch", return_value=data), patch.object(updater, "probe") as probe:
            result = updater.install(self.root, self.root, value)
            probe.assert_called_once()
        self.assertEqual(result["state"], "updated")
        self.assertEqual(selected(self.root), "7.3.2")
        self.assertTrue((self.root / "7.3.1/edge_tts/__init__.py").is_file())
        self.assertEqual(json.loads((self.root / "active.json").read_text())["previous"], "7.3.1")

    def test_daily_throttle_and_force(self):
        with patch.object(updater, "fetch", side_effect=OSError("offline")) as fetch:
            first = updater.check(self.root, self.root, False)
            second = updater.check(self.root, self.root, False)
            self.assertEqual(first, second)
            self.assertEqual(fetch.call_count, 1)
            updater.check(self.root, self.root, True)
            self.assertEqual(fetch.call_count, 2)

    def test_lock_released_and_parallel_check_refused(self):
        with updater.update_lock(self.root):
            self.assertEqual(updater.check(self.root, self.root, True)["state"], "busy")
        with updater.update_lock(self.root):
            pass

    def test_corrupt_or_escaping_pointer_falls_back(self):
        for value in ("{", '{"version":"../outside"}', "[]", '{"version":"7.3.2"}'):
            (self.root / "active.json").write_text(value)
            self.assertEqual(selected(self.root), "bundled")


if __name__ == "__main__":
    unittest.main()
