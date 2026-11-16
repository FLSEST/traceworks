#!/usr/bin/env bash
# Prints what the register folder of the run holds: the names of its files, how many findings the register listed
# and had resolved when the run started, the findings themselves and the last report.
set -euo pipefail

register="$1"

echo "files: $(ls "$register" | sort | tr '\n' ' ')"
echo "findings: $(wc -l < "$register/findings.jsonl")"
echo "resolved: $(wc -l < "$register/resolved.jsonl")"
echo "items: $(wc -l < "$register/items.jsonl")"
echo "--- findings.jsonl"
cat "$register/findings.jsonl"
echo "--- resolved.jsonl"
cat "$register/resolved.jsonl"
echo "--- previous-report.md"
cat "$register/previous-report.md"
