#!/usr/bin/env bash
# What changed in the workspace since a base commit, as git says it, written down for the area finder.
#
#   changes.sh OUT_DIR SINCE PREVIOUS_REPORT        (run in the workspace)
#
# SINCE is the run's input: a commit, a tag or a date (YYYY-MM-DD, with or without a time). Empty means: the
# workspace commit of the last run of this audit, which that run's report names in its first "FACT changes_head:"
# line; PREVIOUS_REPORT is that report as the register hands it to this run. Without either there is no base, and
# the script refuses: it never takes "everything" or "nothing" for a base nobody named.
#
# It writes into OUT_DIR: base, head (commit ids), from ("input" or "register"), since, previous-head, dirty
# ("true" when the workspace holds changes that are in no commit), diff (base against the files as they are, no
# context lines) and untracked (the files git does not track and does not ignore, each ended by a NUL). Changes that
# are in no commit count as changed.
#
# Git only reads here. The workspace is not trusted, and neither is its git configuration, so nothing of it may
# start a program: no fsmonitor, no external diff, no textconv, no pager, and no lock file is asked for.
set -euo pipefail

out=$1
since=$2
previous=$3

refuse() {
    # One line of printable characters: the text may hold the input, and it goes to the record of the run
    printf 'security-changes: %s\n' "$(printf '%s' "$1" | tr -c '[:print:]' '?')" >&2
    exit 1
}

g() {
    GIT_OPTIONAL_LOCKS=0 GIT_CONFIG_NOSYSTEM=1 git -c core.fsmonitor=false -c core.quotePath=false \
        -c core.hooksPath=/dev/null --no-pager "$@"
}

mkdir -p "$out"

command -v git > /dev/null 2>&1 || refuse "git is not installed where this step runs"
inside=$(g rev-parse --is-inside-work-tree 2>&1) || true
[ "$inside" = true ] \
    || refuse "the workspace is no git checkout whose history this step can read (git: $inside). A linked worktree keeps its history outside the workspace; run from the main checkout, or with --source"
head=$(g rev-parse --verify --quiet 'HEAD^{commit}') || refuse "the workspace has no commit yet"

# The workspace commit of the last run of this audit: the first such line of its report, which the tool writes
# before any name of the workspace and which holds no text of a model
previous_head=$(printf '%s\n' "$previous" | sed -n '/^FACT changes_head: [0-9a-f]\{40\}$/{s/^FACT changes_head: //p;q;}')

from=input
if [ -z "$since" ]; then
    from=register
    since=$previous_head
    [ -n "$since" ] || refuse "no base: this audit has no earlier run that names its workspace commit, and the run got no input 'since'. Name the commit, the tag or the date the changes count from: --input since=<commit|tag|YYYY-MM-DD>"
fi

case $since in
    -*) refuse "the base '$since' starts with '-', which git would read as an option" ;;
esac

if printf '%s' "$since" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}([T ][0-9]{2}:[0-9]{2}(:[0-9]{2})?(Z|[+-][0-9]{2}:?[0-9]{2})?)?$'; then
    # A date: the base is the last commit before it. A date without a time means its first second, since git
    # would otherwise take the time of day it is now
    when=$since
    case $when in
        ????-??-??) when="$when 00:00:00" ;;
    esac
    base=$(g rev-list -1 --before="$when" HEAD)
    [ -n "$base" ] || refuse "no commit of the workspace is older than $since: there is no base to measure from. Name a commit instead"
else
    base=$(g rev-parse --verify --quiet --end-of-options "$since^{commit}") || {
        if [ "$from" = register ]; then
            refuse "the last run of this audit was at commit $since, which this checkout does not have. Name the base: --input since=<commit|tag|YYYY-MM-DD>"
        fi
        refuse "the base '$since' names no commit of this checkout"
    }
fi

printf '%s\n' "$base" > "$out/base"
printf '%s\n' "$head" > "$out/head"
printf '%s\n' "$from" > "$out/from"
if [ "$from" = input ]; then printf '%s\n' "$since" > "$out/since"; else : > "$out/since"; fi
printf '%s\n' "$previous_head" > "$out/previous-head"

g diff --no-ext-diff --no-textconv --no-color --no-renames --relative -U0 --src-prefix=a/ --dst-prefix=b/ \
    "$base" -- > "$out/diff"
g ls-files --others --exclude-standard -z > "$out/untracked"
if g diff --no-ext-diff --quiet HEAD -- && [ ! -s "$out/untracked" ]; then
    printf 'false\n' > "$out/dirty"
else
    printf 'true\n' > "$out/dirty"
fi

printf 'FACT changes_base: %s\n' "$base"
printf 'FACT changes_head: %s\n' "$head"
printf 'FACT changes_base_from: %s\n' "$from"
printf 'FACT changes_uncommitted: %s\n' "$(cat "$out/dirty")"
