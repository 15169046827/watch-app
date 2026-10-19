"""Security baseline positive/negative tests without loading model weights."""

import unittest

from runtime_security import validate_runtime


class RuntimeSecurityTests(unittest.TestCase):
    def test_tested_cpu_runtime_passes(self):
        versions = {
            "transformers": "5.10.1",
            "torch": "2.13.0+cpu",
        }
        self.assertEqual(
            validate_runtime(versions.__getitem__, (3, 11))["transformers"], "5.10.1"
        )

    def test_old_python_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "Python 3.11"):
            validate_runtime(lambda _: "5.10.1", (3, 9))

    def test_old_or_untested_versions_rejected(self):
        for package, bad in [
            ("transformers", "4.27.4"),
            ("transformers", "5.10.0"),
            ("transformers", "5.10.1rc1"),
            ("transformers", "6.0.0"),
            ("torch", "2.8.0"),
            ("torch", "2.13.0.post1"),
        ]:
            with self.subTest(package=package, version=bad):
                versions = {
                    "transformers": "5.10.1",
                    "torch": "2.13.0",
                }
                versions[package] = bad
                with self.assertRaisesRegex(RuntimeError, package):
                    validate_runtime(versions.__getitem__, (3, 11))


if __name__ == "__main__":
    unittest.main()
