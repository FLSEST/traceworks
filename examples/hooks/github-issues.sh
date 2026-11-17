#!/usr/bin/env bash
# An example hook for hooks.register_changed (docs/registers.md, "Hooks"): it opens one GitHub issue per new or
# reopened finding of a register, with the GitHub CLI (gh issue create), and says how many findings were resolved.
#
#   github-issues.sh [OWNER/REPO]     the diff of the register as JSON on standard input
#
# The repository is the argument, or GH_REPO from the environment; the hook runs in the register's folder, which is
# no clone, so gh cannot guess it. gh must be logged in (gh auth login) or have GH_TOKEN. Needs gh and jq. Without
# either, or without a repository, it says so on standard error, creates nothing and exits with 2.
set -euo pipefail

missing() { echo "github-issues.sh: $1; no issue was created" >&2; exit 2; }
command -v jq >/dev/null || missing "jq is not on the PATH"
command -v gh >/dev/null || missing "the GitHub CLI (gh) is not on the PATH"
repo="${1:-${GH_REPO:-}}"
[ -n "$repo" ] || missing "no repository: pass OWNER/REPO as the hook's argument, or set GH_REPO"
[[ "$repo" =~ ^[^/[:space:]]+/[^/[:space:]]+(/[^/[:space:]]+)?$ ]] \
    || missing "\"$repo\" is no repository: write it as OWNER/REPO or HOST/OWNER/REPO"

diff="$(cat)"
register="$(jq -r '.register' <<<"$diff")"
if [ "$(jq -r '.report_valid' <<<"$diff")" != "true" ]; then
    echo "register $register: the report of run $(jq -r '.run_id' <<<"$diff") was rejected; no issue was created" >&2
    exit 0
fi

jq -c '.new[], .reopened[]' <<<"$diff" | while read -r finding; do
    title="$(jq -r '"[\(.severity)] \(.rule_key): \(.subject)"' <<<"$finding")"
    body="$(jq -r --arg register "$register" --arg run "$(jq -r '.run_id' <<<"$diff")" '
        "Register `\($register)`, finding `\(.id)`, run `\($run)`\(if .reason then ", reopened: \(.reason)" else "" end).",
        "", .title, "", (.description // "" | select(. != "") | ., ""), "Evidence: \(.evidence // "(none)")", "",
        "Details: `traceworks findings \($register) show \(.id)`"' <<<"$finding")"
    gh issue create --repo "$repo" --title "$title" --body "$body" >/dev/null
    echo "opened an issue in $repo: $title" >&2
done

count="$(jq -r '.resolved | length' <<<"$diff")"
if [ "$count" != "0" ]; then
    echo "register $register: $count finding(s) resolved; close their issues by hand" >&2
fi
