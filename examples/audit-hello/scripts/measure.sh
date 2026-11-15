#!/usr/bin/env bash
# Measures the workspace (the working directory): how many files it holds, and which of them have more than
# LIMIT lines. The facts go to standard output as a fact sheet, which the register uses to check the evidence of
# every finding; the long files go to the scratch directory, where the report step reads them.
# Symbolic links are not followed and not counted.
set -euo pipefail

scratch="$1"
limit=10
list="$scratch/long-files.tsv"
: > "$list"

files=0
while IFS= read -r -d '' file; do
  files=$((files + 1))
  lines="$(wc -l < "$file")"
  if [ "$lines" -gt "$limit" ]; then
    printf '%s\t%s\n' "${file#./}" "$lines" >> "$list"
  fi
done < <(find . -type f -print0 | sort -z)

echo "## FACT SHEET hello"
echo "FACT files: ${files}"
echo "FACT long_files: $(wc -l < "$list" | tr -d ' ')"
while IFS=$'\t' read -r file lines; do
  echo "HIT long_file: ${file} (${lines} lines)"
done < "$list"
