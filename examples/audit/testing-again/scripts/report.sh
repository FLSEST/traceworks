#!/usr/bin/env bash
# Writes the report of the audit from <scratch>/findings.json and the answers of the rating loop on standard input:
# a short markdown, the gate and the audit result envelope the register reads. <workspace> is the project, which
# the grounds of the answers point into.
# - A method that was not asked about keeps its severity and its reason.
# - A method that was never rated takes the severity of its answer. Without an answer it is MEDIUM from ten
#   unnoticed changes, else LOW, and is asked about again next time.
# - A method that was rated before keeps its severity when the answer keeps it, and when there is no answer (then
#   it is asked about again). An answer with another severity is a change, and a change is taken only on a ground
#   this script finds in the workspace:
#     to lower:  {"test": "<TestClass>#<testMethod>"}  a file <TestClass>.java below a src/test folder with a line of
#                                                      code that declares the method ("void <testMethod>(") and
#                                                      with the simple name of the rated method's class in it
#                {"line": "<path>:<line>"}             a line of code of a .java file below a src/main folder, in
#                                                      another file than the method's class, that calls or
#                                                      references the method: "<name>(" or "::<name>", for a
#                                                      constructor "new <Class>" or "<Class>::new"
#     to raise:  {"line": "<path>:<line>"}             a line of code of a .java file below a src/main folder
#   A line of code is not blank and does not begin with //, * or /*. Nothing below a target folder counts.
#   A lowering that is not taken is final: the higher severity stands, and the method is not asked about until it
#   changes again. A raise that is not taken leaves the method not judged: it keeps its severity for now and is
#   asked about again in the next run, because a method that stays LOW is no finding and nobody would look.
#   Nothing is taken on the word of the model alone. A ground that holds exists and names the method; that is all
#   the script can tell. It does not prove that the rating is right.
# A LOW method is counted and not reported: the register holds what a person should decide on. The gate counts what
# is left to decide: a method a person accepted or dismissed does not count, unless it is rated above the severity
# the decision covers. The decisions are those the register held before this run.
# The answers are text of a model: no value of them is run, and every one is put on one line before it is printed.
# The envelope is an HTML comment: every < and > of its JSON is written as < and >, so no text can end the
# comment. A JSON reader gets the text as it was.
set -euo pipefail
scratch=$1 workspace=$2
root=$(realpath -e -- "$workspace")
cat > "$scratch/answers.txt"

# The answers by method. The last answer for a method counts; an entry that is no rating is left out.
jq -n --rawfile answers "$scratch/answers.txt" '
  def oneline: tostring | gsub("[[:cntrl:]]+|\\s+"; " ");
  [ $answers | split("\n")[] | (capture("(?<json>\\{\"ratings\".*\\})\\s*$")? | .json | fromjson? | .ratings[]?)
    | select(type == "object") | select((.method | type) == "string" and (.severity | type) == "string")
    | select(.severity | ascii_upcase | IN("LOW", "MEDIUM", "HIGH"))
    | (if (.ground | type) == "object" then .ground else {} end) as $ground
    | {key: .method, value: {severity: (.severity | ascii_upcase), why: ((.why // "") | oneline | .[:400]),
         ground: (if ($ground.test | type) == "string" then {kind: "test", at: ($ground.test | oneline | .[:300])}
                  elif ($ground.line | type) == "string" then {kind: "line", at: ($ground.line | oneline | .[:300])}
                  else {kind: "none", at: "-"} end)}} ]
  | from_entries' > "$scratch/rated.json"

# One row per change of an earlier rating: the method, the direction, its class and its name, and the ground.
jq -r --slurpfile rated "$scratch/rated.json" '
  def rank: {"LOW": 1, "MEDIUM": 2, "HIGH": 3}[.] // 0;
  .methods[] | select(.ask and (.fresh | not)) | $rated[0][.subject] as $answer
  | select($answer != null and $answer.severity != .before.severity)
  | (.subject | split("#")) as $names
  | [ .subject, (if ($answer.severity | rank) < (.before.severity | rank) then "lower" else "raise" end),
      ($names[0] | split(".") | last), $names[1],
      $answer.ground.kind, (if $answer.ground.at == "" then "-" else $answer.ground.at end) ] | @tsv' \
  "$scratch/findings.json" > "$scratch/changes.tsv"

word='A-Za-z0-9_$'
name_form='^[A-Za-z_$][A-Za-z0-9_$]*$'
line_form='^(.+):([1-9][0-9]{0,6})$'
test_form='^([A-Za-z_][A-Za-z0-9_.]*)#([A-Za-z_][A-Za-z0-9_]*)$'
no_code='^[[:space:]]*(//|\*|/\*|$)'

# A name as it stands in a pattern: only $ of a Java name means something there.
pattern() { printf '%s' "${1//\$/\\\$}"; }

# Prints "ok" when <path>:<line> is a ground to <lower|raise> the rating of <method> of <class>, else why not.
line_ground() {
  local at=$1 direction=$2 class=$3 method=$4 path number file rel text call what
  [[ $at =~ $line_form ]] || { echo "the ground $at is no <path>:<line>"; return; }
  path=${BASH_REMATCH[1]} number=${BASH_REMATCH[2]}
  path=${path#/workspace/}; path=${path#./}
  file=$(realpath -e -- "$root/$path" 2>/dev/null) || file=
  if [[ $path == /* || $file != "$root"/* || ! -f $file ]]; then echo "the workspace has no file $path"; return; fi
  rel=${file#"$root"/}
  if [[ "/$rel" != */src/main/* || $rel != *.java || "/$rel" == */target/* ]]; then
    echo "$path is no .java file below a src/main folder"; return
  fi
  text=$(sed -n "${number}p" -- "$file")
  if [[ $text =~ $no_code ]]; then echo "$path has no code in line $number"; return; fi
  if [ "$direction" = raise ]; then echo ok; return; fi
  if [[ ${rel##*/} == "$class.java" ]]; then
    echo "$path is the file of the method's own class, a caller is in another file"; return
  fi
  if [[ ! $class =~ $name_form ]]; then echo "the class $class cannot be looked for"; return; fi
  if [[ $method == "<"* ]]; then
    what="new $class"
    call="(^|[^$word])new[[:space:]]+$(pattern "$class")([^$word]|\$)|(^|[^$word])$(pattern "$class")::new([^$word]|\$)"
  elif [[ $method =~ $name_form ]]; then
    what=$method
    call="(^|[^$word])$(pattern "$method")[[:space:]]*\\(|::$(pattern "$method")([^$word]|\$)"
  else
    echo "the method $method cannot be looked for"; return
  fi
  [[ $text =~ $call ]] || { echo "line $number of $path does not call or reference $what"; return; }
  echo ok
}

# Prints "ok" when <TestClass>#<testMethod> is a test that names <class>, else why not.
test_ground() {
  local at=$1 class=$2 test method file found='' declared=''
  [[ $at =~ $test_form ]] || { echo "the ground $at is no <TestClass>#<testMethod>"; return; }
  test=${BASH_REMATCH[1]##*.} method=${BASH_REMATCH[2]}
  if [[ ! $class =~ $name_form ]]; then echo "the class $class cannot be looked for"; return; fi
  while IFS= read -r -d '' file; do
    found=yes
    # grep -c reads to the end, so no stage of the pipe is cut off
    if [ "$(grep -vE "$no_code" -- "$file" | grep -cE "(^|[^$word])void[[:space:]]+$method[[:space:]]*\\(")" != 0 ]; then
      declared=yes
      if [ "$(grep -cE "(^|[^$word])$(pattern "$class")([^$word]|\$)" -- "$file")" != 0 ]; then echo ok; return; fi
    fi
  done < <(find "$root" -type f -path '*/src/test/*' -not -path '*/target/*' -name "$test.java" -print0)
  if [ -n "$declared" ]; then echo "the test class $test does not mention $class"
  elif [ -n "$found" ]; then echo "the test class $test declares no method void $method("
  else echo "no src/test folder of the workspace has a class $test"; fi
}

while IFS=$'\t' read -r subject direction class method kind at; do
  case "$direction $kind" in
    "lower test") result=$(test_ground "$at" "$class") ;;
    "lower line") result=$(line_ground "$at" lower "$class" "$method") ;;
    "raise line") result=$(line_ground "$at" raise "$class" "$method") ;;
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
             else {outcome: (if ($answer.severity | rank) < (.before.severity | rank) then "not lowered" else "not raised" end),
                   severity: .before.severity, why: .before.why, ground: .before.ground,
                   wanted: $answer.severity, refusal: ($checked[.subject] // "the answer gives no ground" | oneline)}
             end)
      | . + {undecided: (.covers == null or (.severity | rank) > (.covers | rank)),
             judged: (.ask and (.outcome | IN("unanswered", "not raised") | not))}
      # The kinds a finding keeps are those of its last verdict, so a method that is asked about again is told
      # again what changed
      | . + {kinds: (if .ask and (.judged | not) and .before.gaps != null then .before.gaps else .gaps end)} ] as $methods
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
    "\(count("kept")) methods kept their rating without being asked: their gaps and the decisions on them are what they were. A model was asked about \($asked | length): \($asked | map(select(.fresh)) | length) that were never rated, and \($asked | map(select(.fresh | not)) | length) rated before. Of those rated before, \(count("same")) kept their rating, \(count("taken")) changes were taken on a ground that holds, \(count("not lowered")) lowerings were not taken, and \(count("not raised")) raises were not taken. \(count("unanswered")) methods got no answer. A method without an answer or with a raise that was not taken is asked about again in the next run.",
    "",
    (if count("taken") > 0 then "Changes taken:", "",
       ($methods[] | select(.outcome == "taken") | "- `\(.subject)`: \(.was) to \(.severity) on \(.ground): \(.why // "")"), ""
     else empty end),
    (if count("not lowered") > 0 then "Lowerings not taken, the rating stands:", "",
       ($methods[] | select(.outcome == "not lowered") | "- `\(.subject)`: stays \(.severity), not \(.wanted): \(.refusal)"), ""
     else empty end),
    (if count("not raised") > 0 then "Raises not taken, asked about again in the next run:", "",
       ($methods[] | select(.outcome == "not raised") | "- `\(.subject)`: stays \(.severity) for now, not \(.wanted): \(.refusal)"), ""
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
          description: "\(if .why then "Rating: " + .why + "\n" else "" end)\(if .ground then "Ground: " + .ground + "\n" else "" end)Kinds: \(.kinds)\nSurvived (a test runs the line and stays green): \(.survived[:40] | join(", "))\nNot covered (no test runs the line): \(.uncovered[:40] | join(", "))\nEach place is the line and the mutator of PIT. Kinds are the mutators of the survived places and, after the slash, of the places not covered.",
          evidence: "undetected: \(.fact)",
          item: .subject } ],
        expectedItems: ($methods | length),
        items: [ $methods[] | {name: .subject, digest, judged} ]
      } | tojson | gsub("<"; "\\u003c") | gsub(">"; "\\u003e")) -->"' "$scratch/findings.json"
