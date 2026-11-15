#!/usr/bin/env bash
# An example hook for hooks.register_changed (docs/registers.md): it posts the diff of a register to a TODO service
# over HTTP. It reads the diff JSON on stdin, creates one TODO per new or reopened finding (POST <url>/todos with a
# JSON body and the key in the header X-API-Key) and says how many findings were resolved. Needs curl and jq, the
# base URL of the service in TODO_SERVICE_URL and the file that holds its key in TODO_SERVICE_KEY_FILE;
# TODO_SERVICE_PROJECT names the project the TODOs go into.
#
# This is an example: adapt the request to your service, or write the same for GitHub Issues or Jira. A hook is the
# operator's own code; it runs on the host with the operator's environment, and its exit code goes into the register's log.
set -euo pipefail

diff="$(cat)"
url="${TODO_SERVICE_URL:?set TODO_SERVICE_URL to the base URL of the TODO service}"
key="$(cat "${TODO_SERVICE_KEY_FILE:?set TODO_SERVICE_KEY_FILE to the file that holds the key of the TODO service}")"
project="${TODO_SERVICE_PROJECT:?set TODO_SERVICE_PROJECT to the project id the TODOs go into}"
register="$(jq -r '.register' <<<"$diff")"

if [ "$(jq -r '.report_valid' <<<"$diff")" != "true" ]; then
    echo "register $register: the report of run $(jq -r '.run_id' <<<"$diff") was rejected" >&2
    exit 0
fi

jq -c '.new[], .reopened[]' <<<"$diff" | while read -r finding; do
    title="$(jq -r '"[\(.severity)] \(.rule_key): \(.subject)"' <<<"$finding")"
    body="$(jq -r '"Register \($register), finding \(.id[0:8]).\n\n\(.title)\n\n\(.description // "")\n\nEvidence: \(.evidence // "(none)")"' --arg register "$register" <<<"$finding")"
    priority="$(jq -r 'if .severity == "CRITICAL" then "CRITICAL" elif .severity == "HIGH" then "HIGH" elif .severity == "MEDIUM" then "MEDIUM" else "LOW" end' <<<"$finding")"
    jq -n --arg title "$title" --arg body "$body" --arg priority "$priority" --arg project "$project" \
        '{title: $title, description: $body, priority: $priority, tags: ["audit"], projectId: $project,
          source: "AI", sourceAgent: "traceworks-hook"}' \
        | curl -sS -f -X POST "$url/todos" -H "Content-Type: application/json" -H "X-API-Key: $key" -d @- >/dev/null
    echo "created a TODO: $title" >&2
done

count="$(jq -r '.resolved | length' <<<"$diff")"
if [ "$count" != "0" ]; then
    echo "register $register: $count finding(s) resolved; close their TODOs by hand" >&2
fi
