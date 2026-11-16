#!/usr/bin/env bash
# Writes <scratch>/findings.json from what PIT measured (scripts/methods.sh) and what the register knew when the run
# started, and prints the fact sheet the register checks the evidence against. <register> is the register folder of
# the run ({{ register_dir }}, docs/registers.md): findings.jsonl holds every finding the register still holds,
# whole, and items.jsonl the Java packages. What a person accepted or dismissed goes with the entry of its method,
# with the severity the decision was made on and the severity it covers, and so does the rating of the last report
# with its reason. A Java package whose gaps are what they were when a model last rated them is not rated again.
set -euo pipefail
scratch=$1 register=$2

bash "$(dirname "$0")/methods.sh" "$scratch"

jq --slurpfile known "$register/findings.jsonl" '
  def rank: {"LOW": 1, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4}[.] // 0;
  # A decision covers the severity it was made on, and the severity of the finding too: a finding that is still
  # decided at a higher severity is one the register kept there, as the operator set it
  # (decision_on_higher_severity). The note is one line of at most 200 characters.
  ( [ $known[] | select(.status | IN("ACCEPTED_RISK", "DISMISSED")) | (.disposition.severity // .severity) as $on
      | (.disposition.note // "" | gsub("\\s+"; " ") | .[:200] | if . == "" then "(no note)" else . end) as $note
      | {key: .subject, value: {text: "\(.status) at \($on). Disposition: \($note)",
                                covers: (if ($on | rank) >= (.severity | rank) then $on else .severity end)}} ]
    | from_entries ) as $decided
  # The reason of a rating is the first line of the description, "Rating: ...". A finding without that line that an
  # earlier version of this audit wrote may hold the reason at the end of its evidence, as ". Rated: ...".
  # An accepted or dismissed finding stays in findings.jsonl for a while after a report left it out (absent_reports
  # counts those reports): the method was rated low then, so that finding is no rating of now.
  | ( [ $known[] | select((.absent_reports // 0) == 0) | {key: .subject, value: {severity, why: (
          (.description // "" | capture("^Rating: (?<why>[^\n]*)").why)
          // (.evidence // "" | capture("\\. Rated: (?<why>.*)$").why) // "")}} ]
      | from_entries ) as $before
  | .methods |= map(. + {decided: $decided[.subject].text, covers: $decided[.subject].covers,
                         before: $before[.subject]})' \
  "$scratch/methods.json" > "$scratch/measured.json"

# The digest of a package is what a rating of it sees, without the line numbers: they move with every edit above.
jq -r '.methods | group_by(.package)[]
       | [.[0].package, (map("\(.subject) \([.survived[], "/", .uncovered[]] | map(split(" ")[-1]) | join(",")) \(.decided // "")") | join(";"))]
       | @tsv' "$scratch/measured.json" \
| while IFS=$'\t' read -r package gaps; do
    printf '%s\t%s\n' "$package" "$(printf 'rating-1 %s' "$gaps" | sha256sum | cut -c1-16)"
  done > "$scratch/digests.tsv"

jq --rawfile digests "$scratch/digests.tsv" --slurpfile items "$register/items.jsonl" '
  [ $items[] | select(.state | IN("clean", "decided")) | "\(.verdict_digest) \(.name)" ] as $settled
  | . + {packages: [ $digests | split("\n")[] | select(length > 0) | split("\t")
                     | "\(.[1]) \(.[0])" as $row
                     | {name: .[0], digest: .[1], rate: ($settled | index($row) == null)} ]}' \
  "$scratch/measured.json" > "$scratch/findings.json"

jq -r '"## FACT SHEET testing",
       "FACT mutants: \(.mutants)",
       "FACT survived: \([.methods[].survived | length] | add // 0)",
       "FACT not_covered: \([.methods[].uncovered | length] | add // 0)",
       "FACT packages_to_rate: \([.packages[] | select(.rate)] | length) of \(.packages | length)",
       (.methods[] | "HIT undetected: \(.fact)")' "$scratch/findings.json"
