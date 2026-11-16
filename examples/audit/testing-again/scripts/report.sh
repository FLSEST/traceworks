#!/usr/bin/env bash
# Writes the report of the audit from <scratch>/findings.json and the answers of the rating loop on standard input:
# a short markdown, the gate and the audit result envelope the register reads. <workspace> is the project, which
# the grounds of the answers point into.
# - A method that was not asked about keeps its severity and its reason.
# - A method that was never rated takes the severity of its answer. Without an answer it is MEDIUM from ten
#   unnoticed changes, else LOW, and is asked about again next time.
# - A method that was rated before keeps its severity when the answer keeps it, and when there is no answer. An
#   answer with another severity is a change, and a change is taken only on a ground this script finds in the
#   workspace; otherwise the earlier severity stays and the report lists the change as not taken:
#     to lower:  {"test": "<TestClass>#<testMethod>"}  a file <TestClass>.java below a src/test folder that has
#                                                      the method, or
#                {"line": "<path>:<line>"}             a line of a file of the workspace that names the method
#     to raise:  {"line": "<path>:<line>"}             a line of a file of the workspace that is not blank
#   Nothing is taken on the word of the model alone. A ground says where to look; it does not prove the rating.
# A LOW method is counted and not reported: the register holds what a person should decide on. The gate counts what
# is left to decide: a method a person accepted or dismissed does not count, unless it is rated above the severity
# the decision covers. The decisions are those the register held before this run.
# The answers are text of a model: no value of them is run, and every one is put on one line before it is printed.
set -euo pipefail
scratch=$1 workspace=$2
root=$(realpath -e -- "$workspace")
cat > "$scratch/answers.txt"

# The answers by method. The last answer for a method counts.
jq -n --rawfile answers "$scratch/answers.txt" '
  # One line, and no end or start of an HTML comment: the envelope below is one, and a text must not close it
  def oneline: tostring | gsub("[[:cntrl:]]+|\\s+"; " ") | gsub("-->"; "->") | gsub("<!--"; "<!-");
  [ $answers | split("\n")[] | (capture("(?<json>\\{\"ratings\".*\\})\\s*$")? | .json | fromjson? | .ratings[]?)
    | select(type == "object" and (.method | type) == "string" and (.severity | type) == "string")
    | select(.severity | ascii_upcase | IN("LOW", "MEDIUM", "HIGH"))
    | (if (.ground | type) == "object" then .ground else {} end) as $ground
    | {key: .method, value: {severity: (.severity | ascii_upcase), why: ((.why // "") | oneline | .[:400]),
         ground: (if ($ground.test | type) == "string" then {kind: "test", at: ($ground.test | oneline | .[:300])}
                  elif ($ground.line | type) == "string" then {kind: "line", at: ($ground.line | oneline | .[:300])}
                  else {kind: "none", at: "-"} end)}} ]
  | from_entries' > "$scratch/rated.json"

# One row per change of an earlier rating: the method, the direction, the name a line must hold, and the ground.
jq -r --slurpfile rated "$scratch/rated.json" '
  def rank: {"LOW": 1, "MEDIUM": 2, "HIGH": 3}[.] // 0;
  .methods[] | select(.ask and (.fresh | not)) | $rated[0][.subject] as $answer
  | select($answer != null and $answer.severity != .before.severity)
  | (.subject | split("#")) as $names
  | [ .subject, (if ($answer.severity | rank) < (.before.severity | rank) then "lower" else "raise" end),
      (if ($names[1] | startswith("<")) then $names[0] | split(".") | last else $names[1] end),
      $answer.ground.kind, (if $answer.ground.at == "" then "-" else $answer.ground.at end) ] | @tsv' \
  "$scratch/findings.json" > "$scratch/changes.tsv"

# Prints "ok" when <path>:<line> is a line of a file of the workspace that is not blank and holds <name>, else why not.
line_holds() {
  local at=$1 name=$2 path number file text
  [[ $at =~ ^(.+):([1-9][0-9]{0,6})$ ]] || { echo "the ground $at is no <path>:<line>"; return; }
  path=${BASH_REMATCH[1]} number=${BASH_REMATCH[2]}
  path=${path#/workspace/}; path=${path#./}
  file=$(realpath -e -- "$root/$path" 2>/dev/null) || file=
  if [[ $path == /* || $file != "$root"/* || ! -f $file ]]; then echo "the workspace has no file $path"; return; fi
  text=$(sed -n "${number}p" -- "$file")
  [[ $text =~ [^[:space:]] ]] || { echo "$path has no line $number with text"; return; }
  [[ -z $name || $text == *"$name"* ]] || { echo "line $number of $path does not name $name"; return; }
  echo ok
}

# Prints "ok" when a file <TestClass>.java below a src/test folder of the workspace has the method, else why not.
test_exists() {
  local at=$1 class method file found=
  [[ $at =~ ^([A-Za-z_][A-Za-z0-9_.]*)#([A-Za-z_][A-Za-z0-9_]*)$ ]] || { echo "the ground $at is no <TestClass>#<testMethod>"; return; }
  class=${BASH_REMATCH[1]##*.} method=${BASH_REMATCH[2]}
  while IFS= read -r -d '' file; do
    found=yes
    if grep -qE -- "(^|[^A-Za-z0-9_])${method}[[:space:]]*\\(" "$file"; then echo ok; return; fi
  done < <(find "$root" -type f -path '*/src/test/*' -name "$class.java" -print0)
  if [ -n "$found" ]; then echo "the test class $class has no method $method"
  else echo "the test sources of the workspace have no class $class"; fi
}

while IFS=$'\t' read -r subject direction name kind at; do
  case "$direction $kind" in
    "lower test") result=$(test_exists "$at") ;;
    "lower line") result=$(line_holds "$at" "$name") ;;
    "raise line") result=$(line_holds "$at" "") ;;
    "raise test") result="a rating is raised on the <path>:<line> of the place that breaks, not on a test" ;;
    *) result="the answer gives no ground" ;;
  esac
  printf '%s\t%s\n' "$subject" "$result"
done < "$scratch/changes.tsv" > "$scratch/grounds.tsv"

jq -r --slurpfile rated "$scratch/rated.json" --rawfile grounds "$scratch/grounds.tsv" '
  def rank: {"LOW": 1, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4}[.] // 0;
  def oneline: gsub("[[:cntrl:]]+|\\s+"; " ") | .[:300];
  ($grounds | split("\n") | map(select(length > 0) | split("\t") | {key: .[0], value: (.[1:] | join(" "))})
   | from_entries) as $checked
  | .mutants as $mutants
  | [ .methods[] | ((.survived | length) + (.uncovered | length)) as $n
      | (if .ask then $rated[0][.subject] else null end) as $answer
      | (if $answer != null and $answer.why != "" then $answer.why else .before.why end) as $said
      | . + {n: $n}
      | . + (if .ask | not then {outcome: "kept", severity: .before.severity, why: .before.why, ground: .before.ground}
             elif .fresh and $answer == null
             then {outcome: "unanswered", why: .before.why, ground: null,
                   severity: (.before.severity // (if $n >= 10 then "MEDIUM" else "LOW" end))}
             elif .fresh then {outcome: "fresh", severity: $answer.severity, why: $said, ground: null}
             elif $answer == null
             then {outcome: "unanswered", severity: .before.severity, why: .before.why, ground: .before.ground}
             elif $answer.severity == .before.severity
             then {outcome: "same", severity: .before.severity, why: $said, ground: .before.ground}
             elif $checked[.subject] == "ok"
             then {outcome: "taken", severity: $answer.severity, why: $said, was: .before.severity,
                   ground: "\($answer.ground.kind) \($answer.ground.at)"}
             else {outcome: "refused", severity: .before.severity, why: .before.why, ground: .before.ground,
                   wanted: $answer.severity, refusal: ($checked[.subject] // "the answer gives no ground" | oneline)}
             end)
      | . + {undecided: (.covers == null or (.severity | rank) > (.covers | rank))} ] as $methods
  | def count(outcome): $methods | map(select(.outcome == outcome)) | length;
    ([$methods[].n] | add // 0) as $unnoticed
  | ($methods | map(select(.ask))) as $asked
  | ($methods | map(select(.severity != "LOW"))) as $reported
  | ($reported | map(select(.undecided))) as $open
  | ($open | map(select((.severity | rank) >= ("HIGH" | rank)))) as $high
  | (($reported | length) - ($open | length)) as $decided
  | "# Testing audit, rating again",
    "",
    "PIT changed the code in \($mutants) places. \($unnoticed) changes went unnoticed by the tests, in \($methods | length) methods.",
    "",
    "\(count("kept")) methods kept their rating without being asked: their gaps and the decisions on them are what they were. A model was asked about \($asked | length): \($asked | map(select(.fresh)) | length) that were never rated, and \($asked | map(select(.fresh | not)) | length) rated before. Of those rated before, \(count("same")) kept their rating, \(count("taken")) changes were taken on a ground that holds, and \(count("refused")) changes were not taken. \(count("unanswered")) methods got no answer and are asked about again.",
    "",
    (if count("taken") > 0 then "Changes taken:", "",
       ($methods[] | select(.outcome == "taken") | "- `\(.subject)`: \(.was) to \(.severity) on \(.ground): \(.why // "")"), ""
     else empty end),
    (if count("refused") > 0 then "Changes not taken:", "",
       ($methods[] | select(.outcome == "refused") | "- `\(.subject)`: stays \(.severity), not \(.wanted): \(.refusal)"), ""
     else empty end),
    "\($reported | length) methods are rated above low. A person decided on \($decided) of them; \($open | length) are left to decide, \($high | length) of them high:",
    "",
    ($high[:60][] | "- `\(.subject)`: \(.why // "(no reason was kept)")"),
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
          description: "\(if .why then "Rating: " + .why + "\n" else "" end)\(if .ground then "Ground: " + .ground + "\n" else "" end)Kinds: \(.gaps)\nSurvived (a test runs the line and stays green): \(.survived[:40] | join(", "))\nNot covered (no test runs the line): \(.uncovered[:40] | join(", "))\nEach place is the line and the mutator of PIT. Kinds are the mutators of the survived places and, after the slash, of the places not covered.",
          evidence: "undetected: \(.fact)",
          item: .subject } ],
        expectedItems: ($methods | length),
        items: [ $methods[] | {name: .subject, digest, judged: (.ask and .outcome != "unanswered")} ]
      } | tojson) -->"' "$scratch/findings.json"
