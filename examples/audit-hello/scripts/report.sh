#!/usr/bin/env bash
# Writes the report of the audit: a short markdown, the gate, and the audit result envelope that the register reads.
# The envelope is a full snapshot: every long file is a finding, so a file that is short again is resolved by the
# next run. The evidence of each finding names the fact "long_file" the measure step stated.
set -euo pipefail

scratch="$1"
limit=10

python3 - "$scratch/long-files.tsv" "$limit" <<'PY'
import json
import sys

path, limit = sys.argv[1], int(sys.argv[2])
rows = []
with open(path, encoding="utf-8", errors="replace") as f:
    for line in f:
        line = line.rstrip("\n")
        if line:
            name, lines = line.rsplit("\t", 1)
            rows.append((name, int(lines)))

print("# Long files")
print()
if rows:
    print(f"{len(rows)} file(s) have more than {limit} lines:")
    print()
    for name, n in rows:
        print(f"- `{name}`: {n} lines")
else:
    print(f"No file has more than {limit} lines.")
print()
print("gate_status: " + ("YELLOW" if rows else "GREEN"))
print("gate_category: hello")
print(f"gate_description: {len(rows)} of the files are longer than {limit} lines")

findings = [{
    "ruleKey": "long-file",
    "subject": name,
    "severity": "LOW",
    "title": f"{name} has {n} lines, more than {limit}",
    "description": f"The example counts every file with more than {limit} lines as a finding.",
    "evidence": f"long_file: {name} ({n} lines)",
} for name, n in rows]
envelope = {"snapshotComplete": True, "expectedFindings": len(findings), "findings": findings}
print()
print("<!-- traceworks:audit-result:v1 " + json.dumps(envelope) + " -->")
PY
