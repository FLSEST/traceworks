#!/usr/bin/env bash
# Writes <scratch>/findings.json from what PIT measured (scripts/methods.sh) and what the register knew when the run
# started, and prints the fact sheet the register checks the evidence against. <register> is the register folder of
# the run ({{ register_dir }}, docs/registers.md). The methods are the items of the register.
# Each method gets its earlier verdict:
# - from findings.jsonl, when the register holds a finding of it: the severity, and from the description the reason
#   ("Rating:"), the ground of the last change ("Ground:") and the kinds of its gaps then ("Kinds:");
# - LOW, when the register holds no finding and its item has a verdict: a method rated low is no finding. When
#   resolved.jsonl knows the method, it was a finding before it was rated low, and that rating goes along;
# - from resolved.jsonl, when the method has no verdict any more: it left the measurement and came back.
# A method whose digest is the one of its last verdict is not put to the model ("ask" is false). The digest is the
# method, the kinds of its gaps without line numbers (they move with every edit above) and the decision on it.
# A method without a verdict and without a reason a model gave was never rated: it is rated fresh ("fresh").
set -euo pipefail
scratch=$1 register=$2

bash "$(dirname "$0")/methods.sh" "$scratch"

jq --slurpfile known "$register/findings.jsonl" --slurpfile resolved "$register/resolved.jsonl" \
   --slurpfile items "$register/items.jsonl" '
  def rank: {"LOW": 1, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4}[.] // 0;
  def kinds: map(split(" ")[-1]) | sort | join(",");
  def line(name): (.description // "") | capture("(^|\n)" + name + ": (?<text>[^\n]*)").text // null;
  def rating: {severity, why: line("Rating"), ground: line("Ground"), gaps: line("Kinds")};
  ($known | map({key: .subject, value: .}) | from_entries) as $held
  | ($resolved | map({key: .subject, value: .}) | from_entries) as $left
  | ($items | map({key: .name, value: .verdict_digest}) | from_entries) as $verdicts
  | .methods |= map(
      $held[.subject] as $finding
      # A decision covers the severity it was made on, and the severity of the finding too: a finding that is still
      # decided at a higher severity is one the register kept there (decision_on_higher_severity)
      | (if $finding != null and ($finding.status | IN("ACCEPTED_RISK", "DISMISSED"))
         then ($finding.disposition.severity // $finding.severity) as $on
              | {text: "\($finding.status) at \($on). Note: \($finding.disposition.note // "" | gsub("\\s+"; " ") | .[:200])",
                 covers: (if ($on | rank) >= ($finding.severity | rank) then $on else $finding.severity end)}
         else null end) as $decision
      | . + {gaps: "\(.survived | kinds)/\(.uncovered | kinds)", decided: $decision.text, covers: $decision.covers,
             verdict: $verdicts[.subject],
             before: (if $finding != null then $finding | rating
                      elif $verdicts[.subject] != null
                      then {severity: "LOW", why: null, ground: null, gaps: null,
                            was: (if $left[.subject] != null then $left[.subject] | rating else null end)}
                      elif $left[.subject] != null then $left[.subject] | rating
                      else null end)})' \
  "$scratch/methods.json" > "$scratch/measured.json"

jq -r '.methods[] | [.subject, "again-1 \(.subject) \(.gaps) \(.decided // "")"] | @tsv' "$scratch/measured.json" \
| while IFS=$'\t' read -r subject seen; do
    printf '%s\t%s\n' "$subject" "$(printf '%s' "$seen" | sha256sum | cut -c1-16)"
  done > "$scratch/digests.tsv"

jq --rawfile digests "$scratch/digests.tsv" '
  # What a list of kinds has that another has not, each kind as often as it is more
  def minus($other): reduce $other[] as $kind (.; index($kind) as $at | if $at == null then . else del(.[$at]) end);
  def parts: split("/") | map(split(",") | map(select(length > 0)));
  def named($what): map("\($what) \(.)");
  def changes($was; $is):
    ($was | parts) as $t | ($is | parts) as $n
    | (($n[0] | minus($t[0]) | named("survived")) + ($n[1] | minus($t[1]) | named("not covered"))) as $added
    | (($t[0] | minus($n[0]) | named("survived")) + ($t[1] | minus($n[1]) | named("not covered"))) as $gone
    | if ($added + $gone | length) == 0 then "the kinds of its gaps are what they were"
      else [ (if ($added | length) > 0 then "added: " + ($added | join(", ")) else empty end),
             (if ($gone | length) > 0 then "went away: " + ($gone | join(", ")) else empty end) ] | join("; ") end;
  def reason: if . == null then "no reason was kept" else "the reason then: " + . end;
  ($digests | split("\n") | map(select(length > 0) | split("\t") | {key: .[0], value: .[1]}) | from_entries) as $now
  | .methods |= map(
      . + {digest: $now[.subject]}
      | . + {ask: (.verdict == null or .verdict != .digest),
             fresh: (.verdict == null and (.before == null or .before.why == null))}
      | . + {brief: ([
          .subject,
          " survived: \(.survived | join(", "))",
          " not covered: \(.uncovered | join(", "))",
          (if .fresh then " earlier rating: none, rate it fresh"
           elif .before.was != null
           then " earlier rating: LOW. Before that it was \(.before.was.severity), \(.before.was.why | reason)"
           else " earlier rating: \(.before.severity), \(.before.why | reason)" end),
          (if .fresh then empty
           elif .before.gaps == null then " changed since: its gaps or the decision on it; the kinds of its gaps then were not kept"
           else " changed since: \(changes(.before.gaps; .gaps))" end),
          (if .fresh or .before.ground == null then empty else " ground of the last change: \(.before.ground)" end),
          (if .decided != null then " a person decided: \(.decided)" else empty end)
        ] | join("\n"))})' \
  "$scratch/measured.json" > "$scratch/findings.json"

jq -r '"## FACT SHEET testing",
       "FACT mutants: \(.mutants)",
       "FACT survived: \([.methods[].survived | length] | add // 0)",
       "FACT not_covered: \([.methods[].uncovered | length] | add // 0)",
       "FACT methods_to_rate: \([.methods[] | select(.ask)] | length) of \(.methods | length)",
       (.methods[] | "HIT undetected: \(.fact)")' "$scratch/findings.json"
