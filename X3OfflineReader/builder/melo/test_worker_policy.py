"""Verify the inference boundary without importing any third-party model."""

import base64
import contextlib
import io
import os
import sys
from types import ModuleType
import unittest
from unittest.mock import Mock, patch

import worker


class WorkerPolicyTests(unittest.TestCase):
    def run_worker(self, voice, model):
        encoded = base64.b64encode("公开中文。".encode()).decode()
        request = "unused.wav\t" + voice + "\t1.0\t" + encoded + "\nQUIT\n"
        output = io.StringIO()
        with (
            patch.object(sys, "argv", ["worker.py"]),
            patch.object(sys, "stdin", io.StringIO(request)),
            contextlib.redirect_stdout(output),
            contextlib.redirect_stderr(io.StringIO()),
        ):
            with patch.object(worker, "model_for", return_value=model) as loader:
                worker.main()
        return output.getvalue(), loader

    def test_non_packaged_language_never_loads_model(self):
        output, loader = self.run_worker("../other-model", Mock())
        self.assertIn("supports only the packaged ZH", output)
        loader.assert_not_called()

    def test_sentence_printing_is_disabled(self):
        model = Mock()
        model.hps.data.spk2id = {"ZH": 0}
        output, _ = self.run_worker("ZH", model)
        self.assertEqual(output, "READY\nOK\n")
        self.assertTrue(model.tts_to_file.call_args.kwargs["quiet"])

    def test_restricted_loader_overrides_unsafe_inherited_setting(self):
        api = ModuleType("melo.api")
        api.TTS = Mock(return_value="public-model")
        package = ModuleType("melo")
        with (
            patch.dict(sys.modules, {"melo": package, "melo.api": api}),
            patch("runtime_security.validate_runtime"),
            patch.dict(os.environ, {"TORCH_FORCE_NO_WEIGHTS_ONLY_LOAD": "1"}),
        ):
            self.assertEqual(worker.model_for("ZH"), "public-model")
            self.assertEqual(os.environ["TORCH_FORCE_WEIGHTS_ONLY_LOAD"], "1")
            self.assertNotIn("TORCH_FORCE_NO_WEIGHTS_ONLY_LOAD", os.environ)


if __name__ == "__main__":
    unittest.main()
