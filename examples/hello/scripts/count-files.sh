#!/usr/bin/env bash
# Counts the files in the working directory (the run's workspace) and reports a green gate.
# Symbolic links are not followed and not counted.
set -euo pipefail

count="$(find . -type f | wc -l | tr -d ' ')"
echo "Files in the workspace: ${count}"
echo "gate_status: GREEN"
echo "gate_category: hello"
echo "gate_description: counted the files"
