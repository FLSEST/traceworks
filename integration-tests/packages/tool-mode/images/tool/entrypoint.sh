#!/bin/sh
# The tool of the tool-mode integration tests. It behaves by its first argument; it needs no network.
#
#   args ARG...         prints each argument on its own line, as it arrived
#   fail CODE           prints a line and exits with CODE
#   write FILE          writes a line into FILE
#   cat FILE            prints FILE
#   net                 prints the network interfaces it sees
#   cache DIR           appends a line to DIR/count.log and prints how many lines it holds
#   env NAME            prints whether NAME is set, then its value once
#   none                prints a line and writes nothing
#   sarif DIR           writes DIR/tool.sarif: five results, one suppressed, each snippet with the marker below
#   sarif-clean DIR     writes DIR/tool.sarif with no result
#   sleep DIR           writes DIR/tool.sarif, then sleeps for two minutes
#   flood               prints lines of 4 KiB without end
#   flood-sarif DIR     writes DIR/tool.sarif, then prints lines of 4 KiB without end
set -u

MARKER="SNIPPET-MARKER-7f3a9c"

write_sarif() {
    cat > "$1/tool.sarif" <<SARIF
{
  "version": "2.1.0",
  "runs": [{
    "tool": {"driver": {"name": "ittool", "version": "1.2.3", "rules": [
      {"id": "IT001", "shortDescription": {"text": "a critical finding"}},
      {"id": "IT002", "shortDescription": {"text": "a high finding"}},
      {"id": "IT003", "shortDescription": {"text": "a medium finding"}},
      {"id": "IT004", "shortDescription": {"text": "a low finding"}},
      {"id": "IT005", "shortDescription": {"text": "a finding that is suppressed"}}
    ]}},
    "results": [
      {"ruleId": "IT001", "message": {"text": "critical thing"},
       "properties": {"security-severity": "9.5"},
       "locations": [{"physicalLocation": {"artifactLocation": {"uri": "file:///workspace/src/app.txt"},
         "region": {"startLine": 3, "snippet": {"text": "token = $MARKER"}},
         "contextRegion": {"snippet": {"text": "context $MARKER"}}}}]},
      {"ruleId": "IT002", "level": "error", "message": {"text": "high thing"},
       "locations": [{"physicalLocation": {"artifactLocation": {"uri": "/workspace/lib/util.txt"},
         "region": {"startLine": 7, "snippet": {"text": "$MARKER"}}}}]},
      {"ruleId": "IT003", "level": "warning", "message": {"text": "medium thing"},
       "locations": [{"physicalLocation": {"artifactLocation": {"uri": "docs/readme.txt"},
         "region": {"startLine": 1, "snippet": {"text": "$MARKER"}}}}]},
      {"ruleId": "IT004", "level": "note", "message": {"text": "low thing"},
       "locations": [{"physicalLocation": {"artifactLocation": {"uri": "notes.txt"}}}]},
      {"ruleId": "IT005", "level": "error", "message": {"text": "suppressed thing"},
       "suppressions": [{"kind": "inSource"}],
       "locations": [{"physicalLocation": {"artifactLocation": {"uri": "gone.txt"},
         "region": {"startLine": 1, "snippet": {"text": "$MARKER"}}}}]}
    ]
  }]
}
SARIF
}

write_clean_sarif() {
    cat > "$1/tool.sarif" <<'SARIF'
{"version": "2.1.0", "runs": [{"tool": {"driver": {"name": "ittool", "version": "1.2.3"}}, "results": []}]}
SARIF
}

line=$(printf '%4095s' '' | tr ' ' y)

command=${1:-}
[ $# -gt 0 ] && shift
case "$command" in
    args)
        for argument in "$@"; do
            printf '%s\n' "$argument"
        done
        ;;
    fail)
        echo "failing with $1"
        exit "$1"
        ;;
    write)
        echo written > "$1" && echo "wrote $1"
        ;;
    cat)
        cat "$1"
        ;;
    net)
        ls /sys/class/net
        ;;
    cache)
        echo run >> "$1/count.log"
        wc -l < "$1/count.log" | tr -d ' '
        ;;
    env)
        if [ -n "$(printenv "$1")" ]; then
            echo "$1 is set"
            echo "value is $(printenv "$1")"
        else
            echo "$1 is unset"
        fi
        ;;
    none)
        echo "nothing written"
        ;;
    sarif)
        write_sarif "$1"
        echo "wrote 5 results"
        ;;
    sarif-clean)
        write_clean_sarif "$1"
        echo "wrote no result"
        ;;
    sleep)
        write_sarif "$1"
        echo "going to sleep"
        sleep 120
        echo "woke up"
        ;;
    flood)
        yes "$line"
        ;;
    flood-sarif)
        write_sarif "$1"
        yes "$line"
        ;;
    *)
        echo "unknown command: $command" >&2
        exit 64
        ;;
esac
