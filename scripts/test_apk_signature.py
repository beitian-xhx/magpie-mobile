#!/usr/bin/env python3
"""Offline pin regression: no APK build or signing key access."""
import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("apk_signature", Path(__file__).with_name("verify-apk-signature.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class SignaturePinTest(unittest.TestCase):
    def invoke(self, fingerprints):
        output = "\n".join("Signer #%d certificate SHA-256 digest: %s" % (index + 1, value)
                           for index, value in enumerate(fingerprints))
        with patch("sys.argv", ["verify", "--apk", "synthetic.apk", "--apksigner", "synthetic-signer"]), \
             patch.object(verifier.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, output)):
            verifier.main()

    def test_expected_single_certificate(self):
        self.invoke([verifier.EXPECTED_SHA256])

    def test_wrong_certificate_refuses_publication(self):
        with self.assertRaises(SystemExit):
            self.invoke(["0" * 64])

    def test_unsigned_or_multiple_signers_refuse_publication(self):
        for certificates in [[], [verifier.EXPECTED_SHA256] * 2]:
            with self.assertRaises(SystemExit):
                self.invoke(certificates)

    def test_pipeline_has_fail_closed_shell(self):
        workflow = Path(__file__).parents[1].joinpath(".github/workflows/build.yml").read_text()
        step = workflow.split("- name: Verify pinned APK signing certificate", 1)[1].split("- name:", 1)[0]
        self.assertIn("set -euo pipefail", step)
        self.assertIn("verify-apk-signature.py", step)


if __name__ == "__main__":
    unittest.main()
