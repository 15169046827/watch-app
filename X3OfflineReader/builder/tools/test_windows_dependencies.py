"""Positive and negative graph fixtures; no alert suppression or lock rewriting."""

import unittest

from check_windows_dependencies import check_graph


class WindowsDependencyTests(unittest.TestCase):
    def test_supported_windows_graph(self):
        check_graph(["tingdu-workshop v0.7.0", "wry v0.55.1", "webview2-com v0.38.2"])

    def test_empty_graph_is_not_a_pass(self):
        with self.assertRaisesRegex(ValueError, "root package"):
            check_graph([])

    def test_gtk_family_rejected(self):
        for package in ("glib", "gtk", "webkit2gtk"):
            with self.subTest(package=package):
                with self.assertRaisesRegex(ValueError, "Unsupported GTK"):
                    check_graph(["tingdu-workshop v0.7.0", package + " v0.18.5"])


if __name__ == "__main__":
    unittest.main()
