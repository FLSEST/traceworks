# Security check of the changes

The short check beside the [security self-audit](../security/README.md). The long audit judges every area of the
sources, about 110 calls for a first run. This one judges only what changed since a base commit, and what is one
call away from it: a handful of calls after a day's work. It asks the same question with the same tool, the same
rules, the same judge and the same evidence check, and keeps a register of its own, `security-changes`.

```
changes   (shell, the shell image)   finds the base, compares the files with it     -> changes/ (base, head, manifest, ...)
areas     (shell, ./images/areas)    picks the changed areas and their neighbours   -> areas.jsonl, areas-summary.json
verdicts  (foreach, AI, tools: read) one verdict per picked area, its code and the changed code next to it in the prompt
report    (shell, ./images/areas)    checks the evidence, counts, sets the gate     -> security-changes-report.md
```

## Run it

From the root of a Traceworks checkout, which is the workspace the check reads:

```bash
traceworks images build examples/self-audit/security-changes            # once, and again when images/ changed
traceworks run examples/self-audit/security-changes --input since=v0.1.0       # since a tag
traceworks run examples/self-audit/security-changes --input since=4f2a91c      # since a commit
traceworks run examples/self-audit/security-changes --input since=2026-11-10   # since a date
traceworks run examples/self-audit/security-changes                            # since the last run of this check
traceworks findings security-changes list
```

The judge is picked as in the long audit: `--input judge=fable` (the default), `opus` or `astra`
([../security/README.md](../security/README.md), "The model that judges").

## The base

The changes are measured from one commit, the **base**, to the files of the workspace as they are.

| The run got | The base is |
|---|---|
| `--input since=<commit or tag>` | that commit (`git rev-parse`); a short id works |
| `--input since=<YYYY-MM-DD>`, with or without a time (`2026-11-10T14:00`) | the newest commit on the first-parent line of the workspace commit whose author date and committer date are both before that moment. A date without a zone is UTC, and a date without a time is its first second |
| no `since` | the workspace commit of the last run of this check |
| no `since`, and no earlier run | none: **the run fails in its first step** and says what to give. It never takes "everything" or "nothing" for a base nobody named |

The first step refuses a base that would make the run say more than it looked at:

- **A base that is no ancestor of the workspace commit** (another branch, a rewritten history): the difference to
  it is not what changed in this history.
- **A base that is the workspace commit itself, with a workspace that holds no other change**, when `since` named
  it: there is nothing to check, and a GREEN run would read as an audit. A date can do that (every commit is older
  than it); the message names the commit the date resolved to and its date. Both dates of a commit count because
  whoever makes a commit sets them: a commit that claims to be old in one of them is not taken for old. One that
  claims it in both can be the base; it is then the workspace commit or lies below changes that are compared.
  Without `since` the base may be the workspace commit (nothing happened since the last run); the report then
  begins with `nothing changed since ...` and says so in words.
- **A `since` that leaves commits after the last run unjudged.** When commits lie after the workspace commit of
  the last run and at or before the base, no later run would come back to them. The step refuses and names the
  number; `--input skip=yes` goes on knowingly. The report of such a run names the range and the number, and its
  gate is not GREEN. The same holds when the last run's commit is not in the checkout, so nothing can be counted.

**Where the last run's commit comes from.** A run of an audit gets what its register knew: the placeholders
`{{ audit_findings }}`, `{{ audit_items }}` and `{{ previous_report }}`, and the same as files in the register
folder ([docs/registers.md](../../../docs/registers.md), "What the next run gets"). None of them holds the
workspace commit of a run: a finding and an item name the run that saw them, by its id, and a step cannot read
another run's record. What a run can read is its own last report. So the `report` step prints the commit the
workspace was at right after the gate (`FACT changes_head: <commit>`), the register keeps the report, and the
`changes` step of the next run takes the first such line of `{{ previous_report }}`. The line stands before any
name of the workspace, and no text of a model is in that output. The register hands a run the last report it
*applied*: a run that failed, or whose report the register rejected, does not move the base.

The base, its commit date and the workspace commit are in the output of the `changes` step, in
`areas-summary.json` and in the report.

**Changes that are in no commit count as changed.** A file is compared as it lies in the workspace, and a `.java`
file the base does not have is new, whether git tracks it, ignores it or neither. The report says whether the
workspace differs from its own commit. The next run measures from the commit, so it sees such changes again; an
area that was judged as it is now is not judged twice (below).

## What changed

What changed is decided from **content**, not from what git's diff says. The workspace is not trusted, and git's
diff, status and file listing obey things the workspace sets: an attribute (`*.java -diff`, `binary`) turns a
changed file into "Binary files differ", a bit in the index (`skip-worktree`, `assume-unchanged`) hides one, an
ignore rule leaves a new file out, and a filter named in `.gitattributes` and `.git/config` is a program git
starts. So `scripts/changes.sh` asks git only for objects:

1. the tree of the base (`git ls-tree -r`): every path with its blob id;
2. the blob id of each of those files as it lies in the workspace (`git hash-object --no-filters`): the same id
   is the same bytes;
3. for a `.java` file that differs, the bytes the base has (`git cat-file blob`).

A `.java` file the tree does not have is found by walking the workspace, not below `.git`, a hidden directory,
`target`, `build` or `node_modules`, where the finder does not look either. The area tool then compares the two
versions of each changed `.java` file line by line itself (a longest common subsequence): a line the base does not
have in its place is changed, and where lines were only removed, the two lines around the gap are. Line ends alone
are no change.

**A changed file never drops out silently.** Every file that differs is in the manifest and is counted in the
report: changed, new, removed, and *not compared* (it is no readable regular file any more, or its lines could not
be read). A file that could not be compared is named, and **the gate is not GREEN** while there is one. A changed
file that is no Java source the tool read (a `pom.xml`, a test, a workflow) and a changed place that lies in no
member (an import, the head of a class, an annotation of a type) are counted and named too; no area comes from
them, and the gate does not wait for them.

## What is looked at

The `areas` step finds the areas of the whole project exactly as the long audit does (the same finder, rules and
limits), and then keeps:

| An area is in the run | Its reason, as the prompt and the report show it |
|---|---|
| a changed line lies in it | `changed` |
| it shows a part of a long member, and the member changed in another part | `changed: <member> outside the lines of this area` |
| a member in it calls a member that changed, or is called by one | `neighbour of <member>`, once per changed member |
| the register holds an open finding of it, or calls it open | `an open finding of an earlier run` |
| an earlier run listed it and got no verdict (a failed call, the cap) | `not judged by an earlier run` |

**Neighbours are one call away, in both directions, by the compiler's resolved types.** The finder attributes all
sources in one `javac` task; the same pass that matches the rules notes for every member which members of the
sources it calls, creates or refers to (`Type::method`). A call of a method counts every method of the sources that
overrides it, so a call through an interface reaches each implementation in the project, and a changed
implementation makes the callers of the interface neighbours. A call inside a lambda or an anonymous class belongs
to the member around it. The changed member need not be an area itself: a helper no rule points at makes the areas
that call it neighbours.

**The verdict sees the changed code next to its area.** The prompt holds the area's code as in the long audit and,
in a frame of its own, the code of the changed members the area is connected with (at most 4 members, 60 lines
each; the rest are named). A finding still has to quote a line of the area: the connected code is context, and the
model may read the rest of the workspace (`tools: read`).

**Judged as it is now.** The digest of an item holds the version of the question, the rules, the area's code and
the connected code the prompt showed. A picked area that the register calls clean or decided with exactly that
digest is left out: the same question was answered. That is what keeps a second run over the same uncommitted
change from asking again.

The report names the base and its date, counts the files that differ (removed ones, ones that are no source,
ones that could not be compared), the changed members, the areas with a changed line, the neighbours, the areas
earlier runs left, and **the areas of the project that were not looked at**, and lists every area of the run with
its reason. A run in which no file differs says `Nothing changed.`

## The gate speaks about the changes

The gate follows the items of the run, as in the long audit: RED while an open finding of high severity stands,
YELLOW while an item is open or not judged, a file could not be read or compared, or the run skipped commits
(`skip=yes`); GREEN when every item is settled. The
items here are the areas this check ever picked. So GREEN means: nothing stands in what changed and in what
earlier runs of this check left. It says nothing about the rest of the project, and the description says so in
every run: `of the changes since 4f2a91c03b7e: no verified finding; 3 of 3 areas judged; ...; 104 of 110 areas of
the project not looked at`.

## The register

The check feeds the register `security-changes`, not `security`: the long audit lists every area in every report,
and a report of this check, which lists a few, would take the others out of its register.

- **An item is an area this check picked at least once.** An area nothing ever looked at is no item: listing it
  would leave it "not judged" for good, and the gate could never be GREEN.
- **Every report lists every item the register knows**, read from `items.jsonl` in the register folder of the run,
  which no cap cuts. The items of the run carry their digest and whether a verdict was read. The others are listed
  as the register has them, with the digest of their last verdict, so their state does not move. Since the list is
  whole, the envelope gives `expectedItems`, and an area that is no longer in the sources leaves the register.
- **An open finding is asked about in every run until it is settled.** The rules are judged rules (`catalog.json`),
  so the register never resolves a finding because a report is silent about it. A later run has another base and
  would not come back to the area by itself; so every run takes in the areas with an open finding, whatever
  changed, and wants an answer for each: it holds, or it is gone. A finding whose member is no area any more is
  named as gone by the tool. That costs one call per area with an open finding in every run, until the finding is
  fixed or a person decides it (`traceworks findings security-changes dismiss|accept <id>`).
- **An area without a verdict is judged by the next run.** A failed call or the cap leaves the item `not judged`
  in the register, and the next run takes it in, whatever its base.

So the register has no state that no run comes back to: an item is settled, or the next run judges it.

Decisions do not travel between the two registers. A finding dismissed in `security` is a new finding here when
this check reports it, and the other way round.

## What it needs of git

The `changes` step runs `scripts/changes.sh` in the built-in shell image, which has git and bash. It needs no
network and writes nothing into the workspace, which every container sees read-only. The area tool starts no git;
it reads what the step wrote.

The script runs these git commands and no other, each with `--no-pager`, `--no-replace-objects`,
`GIT_OPTIONAL_LOCKS=0`, `GIT_CONFIG_NOSYSTEM=1` and `GIT_TERMINAL_PROMPT=0`:

| Command | What it reads | Why the workspace cannot make it start a program |
|---|---|---|
| `rev-parse --is-inside-work-tree`, `rev-parse --verify <name>^{commit}` | the repository's layout, a reference, an object | it resolves names; `since` is refused when it starts with `-` and stands after `--end-of-options` |
| `config --get-regexp` | `.git/config` | it prints values and runs none |
| `rev-list --first-parent --format`, `rev-list --count`, `rev-list --no-walk --format`, `merge-base --is-ancestor`, `cat-file -e` | commit objects | they walk the history; replace references are off, so a commit is the commit its id names |
| `ls-tree -r -z` | tree objects | it lists a tree; it does not read the index or the files |
| `hash-object --no-filters --stdin-paths`, `hash-object --stdin` | the files of the workspace | `--no-filters` hashes the bytes as they are: no clean filter, no attribute, no line-end conversion; nothing is written (no `-w`) |
| `cat-file blob <id>` | blob objects | it prints the bytes; text conversion needs `--textconv`, which is not given |

None of them reads or writes the index, so no index bit counts and no `fsmonitor` or `post-index-change` hook is
asked. None of them runs a diff, so no external diff, text conversion or diff attribute counts. None of them
updates a reference or checks a file out, so no hook runs. None of them talks to a remote. **A partial clone is
refused** (`extensions.partialclone`, a `promisor` remote): reading an object it does not have would start a fetch.
**A workspace with a submodule is refused**: the check does not look into one.

What was tried, with git 2.43 on the host (`ChangesScriptTest`): a repository whose configuration names a program
for `diff.external`, `core.fsmonitor`, `core.pager`, a `clean`, `smudge` and `process` filter, `textconv`,
`core.sshCommand`, `core.alternateRefsCommand`, `core.editor`, `credential.helper` and
`uploadpack.packObjectsHook`, with those hooks in `.git/hooks` and the filter and diff driver set for `*.java` in
`.gitattributes`: the program did not run. A file under `-diff`, `binary` and `core.bigFileThreshold=1`, a file
with `skip-worktree` or `assume-unchanged`, and an ignored new file were each found changed; a replace reference
did not move the base. Not tried: other versions of git, and the script in a container.

- **The workspace must hold its history**: a checkout with its `.git` directory. A linked worktree (`git worktree
  add`) has a `.git` file that points outside the workspace, which the container does not see; the step fails and
  says so. Run from the main checkout, or with `--source`.
- **`--source` works**: the checkout of such a run is a full clone of the source, without a depth limit, so the
  base is there as long as a branch or tag of the source reaches it. Such a workspace has no uncommitted changes.
- A base that the checkout does not have (the last run was in another clone, or the commit was rewritten) fails
  the step with the commit named; give `since`.
- The script needs bash 4 and, for a date, the `date -d` of GNU coreutils; the shell image has both. A commit id
  has 40 hex digits, or 64 in a SHA-256 repository.

## What it does not see

- **A change two calls away.** When `a` calls `b` and `b` calls the changed `c`, the area of `a` is not looked at.
- **What is no call.** A changed constant or field that another member reads, a changed annotation, a changed
  supertype without a changed method. And a call the compiler could not resolve: on a library type, or through
  reflection.
- **A base that was never audited.** The check judges the changes; it takes the base as it is. Run the long audit
  once, or accept that the code before the base is unjudged. A `since` later than the last run's commit is
  refused, unless `skip=yes` says that the commits between the two shall stay unjudged.
- **Changes outside the Java sources.** A changed rule file, workflow, Dockerfile or `pom.xml` maps to no area. The
  report counts and names such files.
- **Renamed members.** An item and a finding are known by the file and the member. After a rename the area is a
  new item, the old item leaves the register, and an open finding of the old name is named as gone and comes back
  as new if it still holds, without the decision a person made on the old one.
- **Removed code.** A removed file is counted and has no lines. Where lines were removed inside a member, the
  lines around the gap count as changed. The calls are those of the sources as they are now, so a removed member
  has no neighbours: when an overriding method is removed, its callers still compile, now reach the inherited
  method, and are not looked at unless they changed themselves.
- **New files that are no Java file**, when no commit holds them: the walk for new files looks for `.java` only,
  since nothing else tells a new source from a build's output. A changed or removed file of any kind that the
  base has is counted.
- **A sparse checkout.** A file the base has and the workspace does not is removed, whatever the reason.
- **A neighbour whose call failed is judged without its context.** The next run judges it, but the diff has moved
  on, so its prompt no longer shows the changed code.
- Everything the long audit does not see ([../security/README.md](../security/README.md), "Limits").

## How it shares the tool

`images/areas`, `rules/security.rules` and `catalog.json` are copies of the security package's, so the image's
build context stays inside this package; a test keeps them the same bytes (`ChangesPackageTest`). The tool reads
the changes with `areas find --changes <folder> --items-file <items.jsonl> --register <key>`; without `--changes`
it is the long audit's finder. Change the tool in `../security/images/areas/src` and copy it here.

## Tests

In [`examples/self-audit/tests`](../tests), without a container and without an AI: the calls the finder knows
(`CallsTest`), the line comparison and the picks (`ChangesTest`), the tool and the report (`ChangesMainTest`), the
script against real git repositories, hostile ones among them (`ChangesScriptTest`; it needs bash and git and
fails without them), the package as it ships (`ChangesPackageTest`), and the
package through the engine into its register across runs with different bases, with a shell loop in the model's
place (`ChangesRoundTripTest`). `SecurityChangesScriptIT` in `integration-tests` runs the script in the shell image
(`mvn -Pit`); it is written and was not run.
