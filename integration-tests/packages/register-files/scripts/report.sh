#!/usr/bin/env bash
# Writes the report of the audit: a short markdown and the audit result envelope. Every file of the workspace is a
# finding, with a description and an evidence of 400 characters, which is more than a row of audit_findings keeps.
set -euo pipefail

workspace="$1"

python3 - "$workspace" <<'PY'
import json
import os
import sys

names = sorted(name for name in os.listdir(sys.argv[1]) if os.path.isfile(os.path.join(sys.argv[1], name)))

print("# Files")
print()
print(f"{len(names)} file(s) in the workspace.")
print()
print("the last line of the report")

findings = [{
    "ruleKey": "file",
    "subject": name,
    "severity": "LOW",
    "title": f"{name} is in the workspace",
    "description": f"Remove {name} or say why it stays.",
    "evidence": f"file: {name} " + "x" * 400 + " end-of-evidence",
} for name in names]
envelope = {"snapshotComplete": True, "expectedFindings": len(findings), "findings": findings}
print()
print("<!-- traceworks:audit-result:v1 " + json.dumps(envelope) + " -->")
PY
