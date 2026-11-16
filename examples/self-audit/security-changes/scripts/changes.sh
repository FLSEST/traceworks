#!/usr/bin/env bash
# What only git knows, handed to the area tool: the base of a check of the changes, and objects of the repository.
#
#   changes.sh base OUT_DIR SINCE PREVIOUS_REPORT [SKIP]     (run in the workspace)
#   changes.sh blobs OUT_DIR                                 (run in the workspace)
#
# "base" finds the base commit and writes it down with the tree of the base and of the workspace commit.
# SINCE is the run's input: a commit, a tag or a date (YYYY-MM-DD, with or without a time, read as UTC unless it
# names a zone). Empty means: the workspace commit of the last run of this audit, which that run's report names in
# its first "FACT changes_head:" line; PREVIOUS_REPORT is that report as the register hands it to this run. Without
# either there is no base, and the script refuses: it never takes "everything" or "nothing" for a base nobody named.
# SKIP is "yes" when the operator knows that SINCE leaves commits after the last run unjudged.
# It writes into OUT_DIR: base-commit, head (commit ids), base-date, from ("input" or "register"), since,
# previous-head, skipped (how many commits lie after the last run's commit and before the base; empty when no
# earlier run is known), base-tree and head-tree (git ls-tree -r -z: mode, type, blob id and path of every file).
#
# "blobs" reads OUT_DIR/wanted, blob ids one a line, which the area tool wrote, and writes their bytes to
# OUT_DIR/blobs (git cat-file --batch).
#
# This script decides nothing about what changed. The area tool does, from content: it computes the blob id of
# every file itself and compares it with the tree. So no file name of the workspace is ever given to git, and git
# never reads a file of the workspace, its index, its ignore rules or its attributes: it resolves names, walks
# commits and prints objects (README, "What it needs of git").
set -euo pipefail

mode=$1
out=$2

refuse() {
    # One line of printable characters: the text may hold the input, and it goes to the record of the run
    printf 'security-changes: %s\n' "$(printf '%s' "$1" | tr -c '[:print:]' '?')" >&2
    exit 1
}

g() {
    GIT_OPTIONAL_LOCKS=0 GIT_CONFIG_NOSYSTEM=1 GIT_NO_REPLACE_OBJECTS=1 GIT_TERMINAL_PROMPT=0 \
        git --no-pager --no-replace-objects "$@"
}

command -v git > /dev/null 2>&1 || refuse "git is not installed where this step runs"
inside=$(g rev-parse --is-inside-work-tree 2>&1) || true
[ "$inside" = true ] \
    || refuse "the workspace is no git checkout whose history this step can read (git: $inside). A linked worktree keeps its history outside the workspace; run from the main checkout, or with --source"
head=$(g rev-parse --verify --quiet 'HEAD^{commit}') || refuse "the workspace has no commit yet"
# A partial clone fetches a missing object when it is read, and a fetch starts programs the configuration names
if g config --get-regexp '^(extensions\.partialclone|remote\..*\.(promisor|partialclonefilter))$' > /dev/null 2>&1; then
    refuse "the workspace is a partial clone: reading its objects could start a fetch. Use a full clone"
fi

if [ "$mode" = blobs ]; then
    # Ids only, checked here too: nothing but hex digits reaches git, and no path at all
    [ -f "$out/wanted" ] || refuse "no list of blobs to hand over"
    if grep -Evq '^([0-9a-f]{40}|[0-9a-f]{64})$' "$out/wanted"; then
        refuse "the list of blobs holds a line that is no blob id"
    fi
    g cat-file --batch < "$out/wanted" > "$out/blobs"
    printf 'FACT changes_base_blobs: %s\n' "$(wc -l < "$out/wanted" | tr -d ' ')"
    exit 0
fi
[ "$mode" = base ] || refuse "unknown mode"
since=$3
previous=$4
skip=${5:-}

rm -rf "$out"
mkdir -p "$out"

# The workspace commit of the last run of this audit: the first such line of its report, which the tool writes
# before any name of the workspace and which holds no text of a model. 40 hex digits, or 64 in a SHA-256 repository
previous_head=$(printf '%s\n' "$previous" | sed -n \
    -e '/^FACT changes_head: [0-9a-f]\{40\}$/{s/^FACT changes_head: //p;q;}' \
    -e '/^FACT changes_head: [0-9a-f]\{64\}$/{s/^FACT changes_head: //p;q;}')

from=input
if [ -z "$since" ]; then
    from=register
    since=$previous_head
    [ -n "$since" ] || refuse "no base: this audit has no earlier run that names its workspace commit, and the run got no input 'since'. Name the commit, the tag or the date the changes count from: --input since=<commit|tag|YYYY-MM-DD>"
fi

case $since in
    -*) refuse "the base '$since' starts with '-', which git would read as an option" ;;
esac

is_date=false
if printf '%s' "$since" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}([T ][0-9]{2}:[0-9]{2}(:[0-9]{2})?(Z|[+-][0-9]{2}:?[0-9]{2})?)?$'; then
    # A date: the base is the newest commit on the first-parent line of the workspace commit that was both written
    # and committed before it. Both dates, because either can be set by whoever makes the commit, and the later of
    # the two is the earliest moment the commit can have reached this line
    is_date=true
    limit=$(date -u -d "$since" +%s 2> /dev/null) || refuse "the date '$since' cannot be read here. Name a commit instead"
    g rev-list --first-parent --format='%H %ct %at' "$head" > "$out/walk"
    base=
    while read -r id committed written; do
        [ "$id" = commit ] && continue
        if [ "$committed" -lt "$limit" ] && [ "$written" -lt "$limit" ]; then
            base=$id
            break
        fi
    done < "$out/walk"
    rm -f "$out/walk"
    [ -n "$base" ] || refuse "no commit of the workspace is older than $since: there is no base to measure from. Name a commit instead"
else
    base=$(g rev-parse --verify --quiet --end-of-options "$since^{commit}") || {
        if [ "$from" = register ]; then
            refuse "the last run of this audit was at commit $since, which this checkout does not have. Name the base: --input since=<commit|tag|YYYY-MM-DD>"
        fi
        refuse "the base '$since' names no commit of this checkout"
    }
fi
base_date=$(g rev-list --no-walk --format='%cI' "$base" | sed -n 2p)

if ! g merge-base --is-ancestor "$base" "$head"; then
    if [ "$from" = register ]; then
        refuse "the last run of this audit was at commit $base, which is no ancestor of the workspace commit $head: the history was rewritten, or this is another branch. Name the base: --input since=<commit|tag|YYYY-MM-DD>"
    fi
    refuse "the base $base is no ancestor of the workspace commit $head: the changes since it are not the changes of this history"
fi

# Commits after the last run's commit that this base leaves out: no later run comes back to them by itself
skipped=
if [ -n "$previous_head" ]; then
    if [ "$from" = register ]; then
        skipped=0
    elif g cat-file -e "$previous_head^{commit}" 2> /dev/null; then
        skipped=$(g rev-list --count "$previous_head..$base")
    else
        skipped=unknown
    fi
    if [ "$skipped" != 0 ] && [ "$skip" != yes ]; then
        if [ "$skipped" = unknown ]; then
            refuse "the last run of this audit was at commit $previous_head, which this checkout does not have, so nothing can tell whether the base $base leaves commits unjudged. Give --input skip=yes to go on all the same"
        fi
        refuse "the base $base lies $skipped commits after the commit of the last run of this audit ($previous_head): this run would leave them unjudged, and no later run comes back to them. Leave 'since' out to go on from the last run, or give --input skip=yes to skip them knowingly"
    fi
fi

printf '%s\n' "$base" > "$out/base-commit"
printf '%s\n' "$head" > "$out/head"
printf '%s\n' "$base_date" > "$out/base-date"
printf '%s\n' "$from" > "$out/from"
if [ "$from" = input ]; then printf '%s\n' "$since" > "$out/since"; else : > "$out/since"; fi
printf '%s\n' "$previous_head" > "$out/previous-head"
printf '%s\n' "$skipped" > "$out/skipped"
g ls-tree -r -z "$base" > "$out/base-tree"
g ls-tree -r -z "$head" > "$out/head-tree"

printf 'FACT changes_base: %s\n' "$base"
printf 'FACT changes_base_date: %s\n' "$base_date"
printf 'FACT changes_head: %s\n' "$head"
printf 'FACT changes_base_from: %s\n' "$from"
printf 'FACT changes_commits_skipped: %s\n' "${skipped:-0}"
