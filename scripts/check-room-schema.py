#!/usr/bin/env python3
"""Run after KSP: exported Room schemas must match their reviewed, versioned baseline."""
import json
from pathlib import Path
import subprocess

path = "app/schemas/cc.fplj.magpiemobile.data.ChatDatabase/1.json"
subprocess.run(["git", "ls-files", "--error-unmatch", path], check=True, capture_output=True)
subprocess.run(["git", "diff", "--exit-code", "--", "app/schemas/"], check=True)
schema = json.loads(Path(path).read_text())["database"]
assert schema["version"] == 1
assert schema["identityHash"] == "9a2f15e5cf2cf935d4a2c7b3cd5da77f"
assert {item["tableName"] for item in schema["entities"]} == {"conversation", "message", "operation", "metadata"}
print("PASS: exported Room v1 matches versioned schema; changes require explicit migration review")
