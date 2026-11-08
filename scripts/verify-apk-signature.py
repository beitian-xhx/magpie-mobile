#!/usr/bin/env python3
"""Verify the public release certificate after building; no secret access."""
import argparse
import re
import subprocess

EXPECTED_SHA256 = "dee1d3f485ee17a6184178eaab318d6333fb8b67e7276146a30a03e1f9ee09d6"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--apksigner", required=True)
    args = parser.parse_args()
    result = subprocess.run([args.apksigner, "verify", "--print-certs", args.apk],
                            text=True, capture_output=True, check=True)
    fingerprints = re.findall(r"Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", result.stdout)
    if fingerprints != [EXPECTED_SHA256]:
        raise SystemExit("APK signing certificate mismatch; publication refused")
    print("PASS: fixed release signing certificate SHA256=" + EXPECTED_SHA256)


if __name__ == "__main__":
    main()
