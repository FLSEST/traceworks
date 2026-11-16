#!/usr/bin/env bash
# Maps PIT's CSV files in <scratch>/pit to <scratch>/methods.json: the number of mutants, and one entry per method
# with a mutant no test detected, with its places as "line mutator" and its line of the fact sheet.
# The CSV has no header: file, class, mutator, method, line, status, killing test. The last field is not quoted, so
# only the first six are read. A nested class counts for the class of its file, a lambda for the method it is in.
set -euo pipefail
scratch=$1

awk -F, -v OFS='\t' '{ sub(/\$.*/, "", $2); n = split($3, m, ".")
       if ($4 ~ /^lambda\$/) { sub(/^lambda\$/, "", $4); sub(/\$[0-9]+$/, "", $4) }
       print $2 "#" $4, m[n], $5, $6 }' "$scratch"/pit/*.csv \
| jq -R -s '
  def places(status): map(select(.status == status)) | sort_by(.line) | map("\(.line) \(.mutator)");
  [ split("\n")[] | select(length > 0) | split("\t")
    | {subject: .[0], mutator: .[1], line: (.[2] | tonumber), status: .[3]} ]
  | { mutants: length,
      methods: [ group_by(.subject)[]
        | {subject: .[0].subject, package: (.[0].subject | split("#")[0] | split(".")[:-1] | join(".")),
           mutants: length, survived: places("SURVIVED"), uncovered: places("NO_COVERAGE")}
        | select((.survived | length) + (.uncovered | length) > 0)
        | . + {fact: "\(.subject) (\(.survived | length) survived, \(.uncovered | length) not covered, of \(.mutants))"} ] }' \
  > "$scratch/methods.json"
