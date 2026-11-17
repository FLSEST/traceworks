#!/usr/bin/env bash
# Writes the report of the audit: a short markdown, the gate, and the audit result envelope that the register reads.
# The envelope is a full snapshot: every long file is a finding, so a file that is short again is resolved by the
# next run. The evidence of each finding names the fact "long_file" the measure step stated.
set -euo pipefail

scratch="$1"
limit=10

# long-files.tsv holds one "<name><TAB><lines>" per long file; a name may hold a tab, so the count is after the last.
# The envelope is written in one line, with ", " and ": " between its parts and every character outside printable
# ASCII as \uXXXX (a pair of them above U+FFFF), so the line stays the same bytes on any terminal and in any record.
jq -R -s -r --argjson limit "$limit" '
  def hex4: [(. / 4096 | floor) % 16, (. / 256 | floor) % 16, (. / 16 | floor) % 16, . % 16]
    | map("0123456789abcdef"[. : . + 1]) | add;
  def ascii: explode | map(if . < 127 then [.]
    else (if . < 65536 then [.] else [55296 + ((. - 65536) / 1024 | floor), 56320 + ((. - 65536) % 1024)] end
          | map("\\u" + hex4) | add | explode) end) | add | implode;
  def dumps: if type == "object" then "{" + (to_entries | map((.key | dumps) + ": " + (.value | dumps)) | join(", ")) + "}"
    elif type == "array" then "[" + (map(dumps) | join(", ")) + "]"
    elif type == "string" then tojson | ascii
    else tojson end;

  [split("\n")[] | select(. != "") | split("\t") | {name: (.[:-1] | join("\t")), n: (.[-1] | tonumber)}] as $rows
  | ($rows | length) as $count
  | [$rows[] | {
      ruleKey: "long-file",
      subject: .name,
      severity: "LOW",
      title: "\(.name) has \(.n) lines, more than \($limit)",
      description: "The example counts every file with more than \($limit) lines as a finding.",
      evidence: "long_file: \(.name) (\(.n) lines)"
    }] as $findings
  | "# Long files",
    "",
    (if $count > 0
     then "\($count) file(s) have more than \($limit) lines:", "", ($rows[] | "- `\(.name)`: \(.n) lines")
     else "No file has more than \($limit) lines." end),
    "",
    "gate_status: \(if $count > 0 then "YELLOW" else "GREEN" end)",
    "gate_category: hello",
    "gate_description: \($count) of the files are longer than \($limit) lines",
    "",
    "<!-- traceworks:audit-result:v1 \({snapshotComplete: true, expectedFindings: $count, findings: $findings} | dumps) -->"
' "$scratch/long-files.tsv"
