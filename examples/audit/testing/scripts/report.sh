#!/usr/bin/env bash
# Writes the report of the audit from <scratch>/findings.json and the answers of the rating loop on standard input:
# a short markdown, the gate and the audit result envelope the register reads.
# A rating changes the severity of a finding that was measured and nothing else: an answer names no new finding and
# removes none. A method of a package that was not rated again keeps the rating the register holds; without one it
# is LOW. In a package that was to be rated, a method without an answer keeps its rating too, and without one it is
# MEDIUM from ten unnoticed changes, else LOW. A LOW method is counted and not reported: the register holds what a
# person should decide on. The packages are the items of the register, so the next run knows which were rated.
# The gate counts what is left to decide: a method a person accepted or dismissed does not count, unless it is rated
# above the severity the decision covers. It stays in the findings, so the register keeps it. The decisions are those
# the register held before this run: what the register makes of this report shows in the gate of the next run.
# The reason of a rating goes on in the evidence, so the next run can keep it. {{ audit_findings }} holds 60 000
# bytes, and a list that was cut rates every package again: so the evidence takes as much of each reason as lets all
# rows fit, and none below 40 characters.
set -euo pipefail

jq -r --rawfile answers /dev/stdin '
  def rank: {"LOW": 1, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4}[.] // 0;
  ( [ $answers | split("\n")[] | (capture("(?<json>\\{\"ratings\".*\\})\\s*$")? | .json | fromjson? | .ratings[]?)
      | select((.method | type) == "string" and ((.severity | ascii_upcase?) | IN("LOW", "MEDIUM", "HIGH")))
      | {key: .method, value: {severity: (.severity | ascii_upcase), why: ((.why // "") | tostring | .[:400])}} ]
    | from_entries ) as $rated
  | .mutants as $mutants
  | (.packages | map({key: .name, value: .}) | from_entries) as $packages
  | [ .methods[] | ((.survived | length) + (.uncovered | length)) as $n
      | . + {n: $n, rated: $rated[.subject],
             severity: ($rated[.subject].severity // .before.severity
                        // (if $packages[.package].rate and $n >= 10 then "MEDIUM" else "LOW" end)),
             why: ($rated[.subject].why // .before.why // "" | if . == "" then null else . end)}
      | . + {undecided: (.covers == null or (.severity | rank) > (.covers | rank))} ] as $methods
  | ($methods | group_by(.package) | map({key: .[0].package, value: all(.[]; .rated)}) | from_entries) as $answered
  | ([$methods[].n] | add // 0) as $unnoticed
  | ($methods | map(select(.severity != "LOW"))) as $reported
  | ($reported | map(select(.undecided))) as $open
  | ($open | map(select((.severity | rank) >= ("HIGH" | rank)))) as $high
  | (($reported | length) - ($open | length)) as $decided
  # A row is the status, the severity, the rule, the subject, the title and the evidence: about 110 bytes and
  # the subject three times. 50 000 leaves room for the head and for characters of more than one byte.
  | ((50000 - ([$reported[] | 110 + 3 * (.subject | utf8bytelength)] | add // 0)) / ([$reported | length, 1] | max)
     | floor | if . > 170 then 170 elif . < 40 then 0 else . end) as $room
  | "# Testing audit",
    "",
    "PIT changed the code in \($mutants) places. \($unnoticed) changes went unnoticed by the tests, in \($methods | length) methods. A model rated \($methods | map(select(.rated)) | length) of them in this run, in \([.packages[] | select(.rate)] | length) of \(.packages | length) packages; the others keep their rating. \($reported | length) methods are rated above low. A person decided on \($decided) of them; \($open | length) are left to decide, \($high | length) of them high:",
    "",
    ($high[:60][] | "- `\(.subject)`: \(.why // "(rated in an earlier run)")"),
    "",
    "gate_status: \(if ($high | length) > 0 then "RED" elif ($open | length) > 0 then "YELLOW" else "GREEN" end)",
    "gate_category: testing",
    "gate_description: \($unnoticed) of \($mutants) changes went unnoticed, in \($methods | length) methods, \($reported | length) of them rated above low; \($open | length) left to decide, \($high | length) of them high",
    "",
    "<!-- traceworks:audit-result:v1 \({
        snapshotComplete: true,
        expectedFindings: ($reported | length),
        findings: [ $reported[] | {
          ruleKey: "undetected-mutants",
          subject: .subject,
          severity: .severity,
          title: "\(.n) of \(.mutants) changes of \(.subject | split(".") | last) go unnoticed by the tests",
          description: "\(if .why then "Rating: " + .why + "\n" else "" end)Survived (a test runs the line and stays green): \(.survived[:40] | join(", "))\nNot covered (no test runs the line): \(.uncovered[:40] | join(", "))\nEach place is the line and the mutator of PIT.",
          evidence: "undetected: \(.fact)\(if .why and $room > 0 then ". Rated: " + .why[:$room] else "" end)" } ],
        expectedItems: (.packages | length),
        items: [ .packages[] | {name, digest, judged: (.rate and $answered[.name])} ]
      } | tojson) -->"' "$1/findings.json"
