#!/usr/bin/env bash
# Maps PIT's CSV files in <scratch>/pit to <scratch>/findings.json, one entry per method with a mutant no test
# detected, and prints the fact sheet the register checks the evidence against. <known> holds the rows of
# {{ audit_findings }}: what a person accepted or dismissed goes with the entry of its method, with the severity
# the decision was made on and the severity it covers, and so does the rating of the last report. <items> holds
# the rows of {{ audit_items }}: a Java package whose gaps are what they were when a model last rated them is not
# rated again.
# The CSV has no header: file, class, mutator, method, line, status, killing test. The last field is not quoted, so
# only the first six are read. A nested class counts for the class of its file, a lambda for the method it is in.
set -euo pipefail
scratch=$1 known=$2 items=$3

awk -F, -v OFS='\t' '{ sub(/\$.*/, "", $2); n = split($3, m, ".")
       if ($4 ~ /^lambda\$/) { sub(/^lambda\$/, "", $4); sub(/\$[0-9]+$/, "", $4) }
       print $2 "#" $4, m[n], $5, $6 }' "$scratch"/pit/*.csv \
| jq -R -s --rawfile known "$known" '
  def rank: {"LOW": 1, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4}[.] // 0;
  def places(status): map(select(.status == status)) | sort_by(.line) | map("\(.line) \(.mutator)");
  [ $known | split("\n")[] | select(test("^[A-Z_]+ \\| [A-Z]+ \\| ")) | split(" | ") ] as $rows
  # A decision was made on the severity of its row, or on the one the row names in "Decided on:". It covers that
  # severity, and the severity of the row too: a row that is still decided at a higher severity is one the register
  # kept there, as the operator set it (decision_on_higher_severity).
  | ( [ $rows[] | select(.[0] | IN("ACCEPTED_RISK", "DISMISSED"))
        | ((map(capture("^Decided on: (?<on>[A-Z]+)") | .on) | first) // .[1]) as $on
        | {key: .[3], value: {text: "\(.[0]) at \($on). \(map(select(startswith("Disposition:"))) | first // "")",
                              covers: (if ($on | rank) >= (.[1] | rank) then $on else .[1] end)}} ]
      | from_entries ) as $decided
  | ( [ $rows[] | {key: .[3], value: {severity: .[1], why: (.[5] // "" | capture("Rated: (?<why>.*)$").why // "")}} ]
      | from_entries ) as $before
  | [ split("\n")[] | select(length > 0) | split("\t")
      | {subject: .[0], mutator: .[1], line: (.[2] | tonumber), status: .[3]} ]
  | { mutants: length,
      # A list of known findings that was cut says nothing about a method it does not show: then every package is rated
      known_complete: ($known | test("\\(\\+[0-9]+ more") | not),
      methods: [ group_by(.subject)[]
        | {subject: .[0].subject, package: (.[0].subject | split("#")[0] | split(".")[:-1] | join(".")),
           mutants: length, survived: places("SURVIVED"), uncovered: places("NO_COVERAGE")}
        | select((.survived | length) + (.uncovered | length) > 0)
        | . + {decided: $decided[.subject].text, covers: $decided[.subject].covers, before: $before[.subject],
               fact: "\(.subject) (\(.survived | length) survived, \(.uncovered | length) not covered, of \(.mutants))"} ] }' \
  > "$scratch/measured.json"

# The digest of a package is what a rating of it sees, without the line numbers: they move with every edit above.
jq -r '.methods | group_by(.package)[]
       | [.[0].package, (map("\(.subject) \([.survived[], "/", .uncovered[]] | map(split(" ")[-1]) | join(",")) \(.decided // "")") | join(";"))]
       | @tsv' "$scratch/measured.json" \
| while IFS=$'\t' read -r package gaps; do
    printf '%s\t%s\n' "$package" "$(printf 'rating-1 %s' "$gaps" | sha256sum | cut -c1-16)"
  done > "$scratch/digests.tsv"

jq --rawfile digests "$scratch/digests.tsv" --rawfile items "$items" '
  [ $items | split("\n")[] | split(" | ") | select(length == 3 and (.[0] | IN("clean", "decided"))) | "\(.[1]) \(.[2])" ] as $settled
  | .known_complete as $complete
  | . + {packages: [ $digests | split("\n")[] | select(length > 0) | split("\t")
                     | "\(.[1]) \(.[0])" as $row
                     | {name: .[0], digest: .[1], rate: (($complete and ($settled | index($row) != null)) | not)} ]}' \
  "$scratch/measured.json" > "$scratch/findings.json"

jq -r '"## FACT SHEET testing",
       "FACT mutants: \(.mutants)",
       "FACT survived: \([.methods[].survived | length] | add // 0)",
       "FACT not_covered: \([.methods[].uncovered | length] | add // 0)",
       "FACT packages_to_rate: \([.packages[] | select(.rate)] | length) of \(.packages | length)",
       (.methods[] | "HIT undetected: \(.fact)")' "$scratch/findings.json"
