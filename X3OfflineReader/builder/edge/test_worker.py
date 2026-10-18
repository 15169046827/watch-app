"""Offline tests for Edge bridge diagnostics; no requests or user text."""
import unittest

from worker import failure_details
from aiohttp import WSServerHandshakeError


class FailureDetailsTest(unittest.TestCase):
    def test_handshake_error_exposes_status_without_url(self):
        error = WSServerHandshakeError(
            request_info=None,
            history=(),
            status=403,
            message="Invalid response status",
        )
        details = failure_details(error)
        self.assertEqual(details["httpStatus"], 403)
        self.assertEqual(details["error"], "Invalid response status")
        self.assertEqual(details["errorType"], "WSServerHandshakeError")
        self.assertIn("clientVersion", details)
        self.assertNotIn("url", str(details).lower())

    def test_other_error_keeps_bounded_diagnosis(self):
        details = failure_details(ValueError("invalid\nvoice " + "x" * 600))
        self.assertEqual(details["errorType"], "ValueError")
        self.assertNotIn("httpStatus", details)
        self.assertNotIn("\n", details["error"])
        self.assertLessEqual(len(details["error"]), 500)


if __name__ == "__main__":
    unittest.main()
