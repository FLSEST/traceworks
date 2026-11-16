#!/usr/bin/env bash
# Which files of the workspace differ from a base commit, written down for the area finder.
#
#   changes.sh OUT_DIR SINCE PREVIOUS_REPORT [SKIP]        (run in the workspace)
#
# SINCE is the run's input: a commit, a tag or a date (YYYY-MM-DD, with or without a time, read as UTC unless it
# names a zone). Empty means: the workspace commit of the last run of this audit, which that run's report names in
# its first "FACT changes_head:" line; PREVIOUS_REPORT is that report as the register hands it to this run. Without
# either there is no base, and the script refuses: it never takes "everything" or "nothing" for a base nobody named.
# SKIP is "yes" when the operator knows that SINCE leaves commits after the last run unjudged.
#
# What changed is decided from content, never from what git's diff, its index, its ignore rules or its attributes
# say: the workspace is not trusted, and all of those can hide a file or start a program. The script lists the
# tree of the base (git ls-tree), computes the blob id of each of its files as it lies in the workspace
# (git hash-object --no-filters), and compares the two; a .java file the base does not have is found by walking
# the workspace. For a changed .java file it writes the bytes of the base (git cat-file blob); the area tool
# compares the lines itself. Git only reads objects and references here: no diff, no status, no index, no filter,
# no attribute, no hook, no pager, no network (README, "What it needs of git").
#
# It writes into OUT_DIR: base, head (commit ids), base-date, from ("input" or "register"), since, previous-head,
# skipped (how many commits lie after the last run's commit and before the base; empty when no earlier run is
# known), dirty ("true" when the workspace differs from its own commit), manifest and base/<n>. The manifest holds
# three fields per file, each ended by a NUL: a status, the path below the workspace, and the number of its file
# under base/ or nothing. M: the file differs from the base. A: a .java file the base does not have. D: the
# workspace no longer has it. U: it could not be compared (not readable, or no regular file any more).
# Changes that are in no commit count as changed.
set -euo pipefail

out=$1
since=$2
previous=$3
skip=${4:-}

refuse() {
    # One line of printable characters: the text may hold the input, and it goes to the record of the run
    printf 'security-changes: %s\n' "$(printf '%s' "$1" | tr -c '[:print:]' '?')" >&2
    exit 1
}

g() {
    GIT_OPTIONAL_LOCKS=0 GIT_CONFIG_NOSYSTEM=1 GIT_NO_REPLACE_OBJECTS=1 GIT_TERMINAL_PROMPT=0 \
        git --no-pager --no-replace-objects "$@"
}

[ "${BASH_VERSINFO[0]}" -ge 4 ] || refuse "this step needs bash 4 or later"
command -v git > /dev/null 2>&1 || refuse "git is not installed where this step runs"
inside=$(g rev-parse --is-inside-work-tree 2>&1) || true
[ "$inside" = true ] \
    || refuse "the workspace is no git checkout whose history this step can read (git: $inside). A linked worktree keeps its history outside the workspace; run from the main checkout, or with --source"
head=$(g rev-parse --verify --quiet 'HEAD^{commit}') || refuse "the workspace has no commit yet"
# A partial clone fetches a missing object when it is read, and a fetch starts programs the configuration names
if g config --get-regexp '^(extensions\.partialclone|remote\..*\.(promisor|partialclonefilter))$' > /dev/null 2>&1; then
    refuse "the workspace is a partial clone: reading its objects could start a fetch. Use a full clone"
fi

rm -rf "$out"
mkdir -p "$out/base"

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

# Compares the tree of a commit with the files of the workspace, by content. With a manifest file it writes one
# record per file that differs, and the base bytes of each changed .java file; it always sets $differing.
compare() {
    local tree=$1 manifest=$2 mode type id path n=0 now
    local -A known=()
    differing=0
    g ls-tree -r -z "$tree" > "$out/tree"
    # First the blob id of every file of the tree as it lies in the workspace, in one call, in the tree's order
    while IFS= read -r -d '' entry; do
        path=${entry#*$'\t'}
        mode=${entry%% *}
        if [ "$mode" != 120000 ] && [ "$mode" != 160000 ] && [ -f "$path" ] && [ ! -L "$path" ] && [ -r "$path" ] \
            && [[ $path != *$'\n'* ]]; then
            printf '%s\n' "$path"
        fi
    done < "$out/tree" > "$out/paths"
    g hash-object --no-filters --stdin-paths < "$out/paths" > "$out/ids"
    exec 3< "$out/ids"
    while IFS= read -r -d '' entry; do
        path=${entry#*$'\t'}
        mode=${entry%% *}
        type=${entry#* }
        type=${type%% *}
        id=${entry%%$'\t'*}
        id=${id##* }
        known[$path]=1
        [ "$type" != commit ] || refuse "the workspace has a submodule ($path): this check does not look into one"
        status=
        if [ "$mode" = 120000 ]; then
            if [ ! -L "$path" ]; then
                if [ -e "$path" ]; then status=U; else status=D; fi
            else
                now=$(readlink -- "$path" | tr -d '\n' | g hash-object --stdin)
                [ "$now" = "$id" ] || status=M
            fi
        elif [ ! -e "$path" ] && [ ! -L "$path" ]; then
            status=D
        elif [ ! -f "$path" ] || [ -L "$path" ] || [ ! -r "$path" ]; then
            status=U
        elif [[ $path == *$'\n'* ]]; then
            now=$(g hash-object --no-filters -- "$path")
            [ "$now" = "$id" ] || status=M
        else
            IFS= read -r now <&3
            [ "$now" = "$id" ] || status=M
        fi
        [ -n "$status" ] || continue
        differing=$((differing + 1))
        [ -n "$manifest" ] || continue
        if [ "$status" = M ] && [ "$mode" != 120000 ] && [[ $path == *.java ]]; then
            n=$((n + 1))
            g cat-file blob "$id" > "$out/base/$n"
            printf '%s\0%s\0%s\0' "$status" "$path" "$n" >> "$manifest"
        else
            printf '%s\0%s\0\0' "$status" "$path" >> "$manifest"
        fi
    done < "$out/tree"
    exec 3<&-
    # Then the .java files the tree does not have, tracked, ignored or neither: the finder compiles what lies
    # there. Not below .git, and not where the finder does not look either: hidden directories, target, build
    # and node_modules
    while IFS= read -r -d '' path; do
        path=${path#./}
        [ -z "${known[$path]:-}" ] || continue
        differing=$((differing + 1))
        [ -z "$manifest" ] || printf 'A\0%s\0\0' "$path" >> "$manifest"
    done < <(find . \( -name '.?*' -o -name target -o -name build -o -name node_modules \) -prune \
        -o -type f -name '*.java' -print0 | LC_ALL=C sort -z)
    rm -f "$out/tree" "$out/paths" "$out/ids"
}

: > "$out/manifest"
compare "$base" "$out/manifest"
changed=$differing
if [ "$base" = "$head" ]; then
    dirty_count=$changed
else
    compare "$head" ""
    dirty_count=$differing
fi
if [ "$dirty_count" -gt 0 ]; then dirty=true; else dirty=false; fi

if [ "$from" = input ] && [ "$base" = "$head" ] && [ "$dirty" = false ]; then
    if [ "$is_date" = true ]; then
        refuse "nothing to check: the date $since resolves to the workspace commit itself ($base, committed $base_date), and the workspace holds no other change. Name an earlier date or a commit"
    fi
    refuse "nothing to check: the base '$since' is the workspace commit itself ($base), and the workspace holds no other change"
fi

printf '%s\n' "$base" > "$out/base-commit"
printf '%s\n' "$head" > "$out/head"
printf '%s\n' "$base_date" > "$out/base-date"
printf '%s\n' "$from" > "$out/from"
if [ "$from" = input ]; then printf '%s\n' "$since" > "$out/since"; else : > "$out/since"; fi
printf '%s\n' "$previous_head" > "$out/previous-head"
printf '%s\n' "$skipped" > "$out/skipped"
printf '%s\n' "$dirty" > "$out/dirty"

printf 'FACT changes_base: %s\n' "$base"
printf 'FACT changes_base_date: %s\n' "$base_date"
printf 'FACT changes_head: %s\n' "$head"
printf 'FACT changes_base_from: %s\n' "$from"
printf 'FACT changes_files: %s\n' "$changed"
printf 'FACT changes_uncommitted: %s\n' "$dirty"
printf 'FACT changes_commits_skipped: %s\n' "${skipped:-0}"
