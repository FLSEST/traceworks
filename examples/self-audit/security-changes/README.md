# Security check of the changes

**What this check is for.** It finds mistakes early, in the time between two full audits: it looks at what changed
since a commit and at what is one call away, where the [security self-audit](../security/README.md) looks at
every area.

**What it costs, measured.** On the last ten merges of this repository's `main` (November 2026, about 140 areas in
the project) it picked 5, 0, 51, 27, 0, 57, 26, 44, 0 and 0 areas. A merge that touches main code costs about 35
calls, roughly a quarter of a full audit, and the neighbours outnumber the changed areas. So it is short per
merge, not per day: run it per merge. It was GREEN in at most 2 of the 10, on a clean `--source` checkout; the
others were YELLOW for files passed unjudged (workflow files, scripts, package READMEs, `AGENTS.md`). Expect
YELLOW with a short list of files to look at yourself as its usual good result.

**What it does not claim.** It selects what to look at by text and by one call. That catches the ordinary mistake
in the code somebody just wrote. It does not protect against someone who hides a change on purpose: a selection
can be steered, and four reviews of this package each found another way past it. The judgement over every area of
the project is the full audit's (`examples/self-audit/security`), and only that one says that the project was
looked at. This check asks the same question with the same tool, rules, judge and evidence check, and keeps a
register of its own, `security-changes`.

**What it cannot see**, whatever its gate says (the list with reasons is at the end, "What it does not see"):

- a change two calls away, and data that flows through more than one call;
- what is no call: a constant, a field, a default or a configuration key that another file reads;
- reflection, and calls on library types the compiler could not resolve;
- every file it names as passed unjudged: scripts, workflows, build files, Java outside the source roots;
- everything before the base: it takes the base as it is;
- what the model reads on its own in the workspace and the digest of an area does not hold.

```
base      (shell, the shell image)   finds the base; hands over the two trees       -> changes/ (base, head, trees)
changed   (shell, ./images/areas)    decides what differs; asks for base blobs by id -> changes/wanted
blobs     (shell, the shell image)   hands over those blobs                          -> changes/blobs
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
traceworks run examples/self-audit/security-changes --input full_audit=4f2a91c # and name the last full audit
traceworks run examples/self-audit/security-changes --input green=no           # never say GREEN
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
  it (the `changed` step refuses this one, since it is the tool that knows what differs): there is nothing to check, and a GREEN run would read as an audit. A date can do that (every commit is older
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
`base` step of the next run takes the first such line of `{{ previous_report }}`. The line stands before any
name of the workspace, and no text of a model is in that output. The register hands a run the last report it
*applied*: a run that failed, or whose report the register rejected, does not move the base.

The base, its commit date and the workspace commit are in the output of the `base` step, in
`areas-summary.json` and in the report.

**When the base moves.** The report of a run names the commit the next run without `since` starts from
(`FACT changes_head`, and a row of the report that says why). It is the workspace commit only when the run judged
everything of the range that a run can judge. It is **this run's base again** when the run left something that a
later run can judge:

| The run | Why the base stays |
|---|---|
| could not read a file of a source root (it does not parse, is no UTF-8, is a link) | once it reads, the next run sees the whole change in it |
| could not compare a changed file | the same |
| cut an area by the cap, or got no verdict for an area (a failed call, an answer that did not verify) | the area is judged by a later run with the changed code still in the diff |
| ran on a workspace that differs from its commit (a `.java` file git ignores counts) | the files were judged, not the commit, which may hold a change they do not have |

What this check can never judge does not hold the base, or it would hold it for ever: a changed script, workflow
or build file, Java code outside the compiled roots, a removed Java file, and commits skipped with `skip=yes`. Such
a run is not GREEN, the report names the files and says **passed unjudged**, and a later run without `since` does not come
back to them (a run with the same `since` again names them again). A run that holds the base judges again only what the register does not call judged as it is.

**Changes that are in no commit count as changed.** A file is compared as it lies in the workspace, and a `.java`
file the base does not have is new, whether git tracks it, ignores it or neither. The report says whether the
workspace differs from its own commit. The next run measures from the commit, so it sees such changes again; an
area that was judged as it is now is not judged twice (below).

## What changed

**One program decides what changed: the area tool, which also compiles the files.** Were a script to decide which
files differ and the tool where their lines are, every difference between the two (which directories are walked,
how a path is written, what ends a line) would be a place where a change is lost. So the git step decides nothing.
It hands over what only git has, and no file name of the workspace ever reaches git:

1. `base`: the commit ids, and the tree of the base and of the workspace commit (`git ls-tree -r -z`: every path
   with its mode and blob id);
2. `changed`: the tool computes the blob id of every file itself (the SHA-1 or SHA-256 of `blob <length>`, a NUL
   and the bytes) and compares it with the trees. It writes the ids of the base blobs it wants: those of the
   changed `.java` files;
3. `blobs`: the bytes of those blobs (`git cat-file --batch`, the ids on standard input, each checked to be hex
   digits and nothing else).

What the workspace sets for git has no say in this: an attribute (`*.java -diff`, `binary`), a bit in the index
(`skip-worktree`, `assume-unchanged`), an ignore rule, a filter, a quoted path.

| A file | Is |
|---|---|
| of the base, with another blob id in the workspace, or another mode (made executable, replaced by a link) | changed |
| of the base, and not in the workspace | removed |
| of the workspace commit and not of the base, whatever its kind | new |
| a `.java` file in neither tree, tracked, ignored or neither, anywhere below the workspace except below a `.git` directory and except what a build leaves (next paragraph) | new |
| of a tree, and no file any more (a directory), or a path that leaves the workspace | not compared |
| a file whose name is no UTF-8, in a tree or on disk | not compared: no text leads back to such a file, so it is a doubt by its name |

**What a build leaves is no file of the workspace.** Outside the source roots, a `.java` file in no commit that
lies below a hidden directory, `target`, `build` or `node_modules` is not looked at: the finder skips those
directories when it searches for source roots and never compiles what is there (a copy of a source in
`target/classes`, the worktrees of a tool), so it neither makes the workspace differ nor counts as a file. Inside
a source root every directory counts, whatever its name, and a file a commit holds is compared wherever it lies.

Every path of either tree and every `.java` file on disk that is looked at has one of these or is unchanged, and none is dropped: a
test puts every status against every kind of file and holds that each ends in changed lines or in a list the report
shows (`ChangesTest`, "noStatusOfNoKindOfFile_isDroppedSilently").

**The lines are compared for exactly the files the finder read**, in `areas find`: for each of them the tool
looks up what differs, and takes the finder's own lines of the file (`String.lines()`, which ends a line where the
compiler does: at a line feed, a carriage return, or both) and the base bytes split the same way. A line the base
does not have in its place is changed (a longest common subsequence decides), and where lines were only removed,
the two lines around the gap are. A new file is changed as a whole, and so is a changed file whose base bytes are
not there or are no UTF-8; that one is named. A test holds that no file the finder reads is left out, also below
a directory called `build`, `target` or `node_modules` or a hidden one inside a source root.

**A changed file never drops out silently.** Every file that differs is counted in the report, by its kind:

| Kind | In the report | The gate |
|---|---|---|
| a Java file the finder read | its changed lines pick areas | follows the items |
| a changed place of such a file in no member (an import, the package, the head of a class, an annotation of a type) | counted and named. Every area of that file is picked (reason `changed: the head of its file`), and every area with a call into that file, or into a file that holds a supertype or a subtype of its classes, is a neighbour: such a line can change what a name in the file means and what a call through its classes reaches. A picked area is judged unless the register holds a verdict for exactly what it is and means now | follows the items |
| a test (`.java` below a `src/test/java`) | counted | no effect. This assumes that test sources are not shipped and not run by the tool; a project that ships them needs another rule |
| documentation | counted | no effect |
| a removed Java file, other than a test | named | **not GREEN**: its areas are gone with it, and what called it was looked at only where it changed |
| another `.java` file the finder did not read (a class moved out of the source roots, the source of a tool) | named | **not GREEN**: code changed that nothing looked at |
| every other file, changed, new or removed (a script, a workflow, a Dockerfile, a `pom.xml`, a resource) | named | **not GREEN**: it can change what the project does, and this check has no word on it |
| a file that could not be compared | named | **not GREEN** |

**Documentation** is a `*.md` file, or a file below the `docs` directory at the top of the workspace, that nothing
reads when the project runs. So not: a file below a `src` or a hidden directory; a file of a workflow package (a
directory with a `workflow.json`, at or above the file: its steps and prompts can read what lies there, its README
included); `AGENTS.md` and `CLAUDE.md`, which an AI tool reads as instructions. Those are files the check cannot
judge.

A GREEN over a changed script would say more than was checked; that is the reason for the rows that say **not
GREEN**.
The price: in the last twenty commits of this repository's main line, 8 changed a file of the "every other" kind and
2 a Java file outside the source roots and the tests, so such runs are YELLOW with that reason until the files are
looked at by a person; 19 changed tests, which is why tests do not count. The base moves on all the same, and the
next run speaks about what changed after it.

**A finding is not named as gone while Java code changed where the finder does not read.** When a member with an
open finding is no area any more, the tool otherwise names the finding as gone. In a run with a changed or new
Java file outside the compiled roots it does not: the member may have moved there. A changed test does not count
for this: tests change with nearly every commit, and a method that was removed must be able to take its finding
with it. The finding stays open, the
report lists it under "Open findings that no item carries", and a person decides.

## What is looked at

The `areas` step finds the areas of the whole project exactly as the long audit does (the same finder, rules and
limits), and then keeps:

| An area is in the run | Its reason, as the prompt and the report show it |
|---|---|
| a changed line lies in it | `changed` |
| it shows a part of a long member, and the member changed in another part | `changed: <member> outside the lines of this area` |
| a line of its file changed that lies in no member (an import, the head of a class) | `changed: the head of its file (outside every member)` |
| a field, a constant or an initializer of its file changed | `changed: a field of its file` |
| a member in it calls a member that changed, or is called by one | `neighbour of <member>`, once per changed member |
| the register holds an open finding of it, or calls it open | `an open finding of an earlier run` |
| an earlier run listed it and got no verdict (a failed call, the cap) | `not judged by an earlier run` |

**Neighbours are one call away, in both directions, by the compiler's resolved types.** The finder attributes all
sources in one `javac` task; the same pass that matches the rules notes for every member which members of the
sources it calls, creates or refers to (`Type::method`). A call of a method counts every method of the sources that
overrides it, so a call through an interface reaches each implementation in the project, and a changed
implementation makes the callers of the interface neighbours. A call inside a lambda or an anonymous class belongs
to the member around it. The changed member need not be an area itself: a helper no rule points at makes the areas
that call it neighbours. A call of something a class has without a line of its own (the constructor nobody wrote,
the canonical constructor and the accessors of a record) goes to the head of the class, the lines before its first
member, so a changed record header makes the areas that create the record neighbours.

**The verdict sees the changed code next to its area.** The prompt holds the area's code as in the long audit and,
in a frame of its own, the code of the changed members the area is connected with (at most 4 members, 60 lines
each; the rest are named). A finding still has to quote a line of the area: the connected code is context, and the
model may read the rest of the workspace (`tools: read`).

**Judged as it is now.** The digest of an item holds the version of the question, the rules, the area's code,
**what that code means** (what each call, creation and method reference of its members resolved to, by declaring
type and signature, and the lines of its file outside every member: package, imports, class heads), and the whole
code of every changed member it is connected with, also what the prompt had no room for (the prompt says where it
cut). So an import or a supertype that points a call of the area at other code changes the digest, though no line
of the area changed, and the area does not pass for judged. So a run with the same `since` as the last one asks again when any line of that code changed. A picked area that the register calls clean or decided with exactly that
digest is left out: the same question was answered. That is what keeps a second run over the same uncommitted
change from asking again.

The report names the base and its date, counts the files that differ (removed ones, ones that are no source,
ones that could not be compared), the changed members, the areas with a changed line, the neighbours, the areas
earlier runs left, and **the areas of the project that were not looked at**, and lists every area of the run with
its reason. A run in which no file differs says `Nothing changed.`

## The gate speaks about the changes

The gate has a category of its own, `changes`, and its description says in one line what its colour covers:

```
GREEN   12 areas picked since 4e12a3c80b1d (3 commits), all judged, nothing found
YELLOW  12 areas picked since 4e12a3c80b1d (3 commits), all judged, nothing found; 3 files passed unjudged
YELLOW  12 areas picked since 4e12a3c80b1d (3 commits), 2 not judged, nothing found; the base does not move
RED     12 areas picked since 4e12a3c80b1d (3 commits), all judged, 1 finding (1 high)
GREEN   nothing changed since 4e12a3c80b1d, no area picked
```

The counts behind it follow as the details (`DETAILS:` in the step's output, `**Details:**` in the report), which
end with how much of the project was not looked at: `104 of 110 areas of the project not looked at`.

| Gate | When |
|---|---|
| RED | an open finding of high severity stands |
| YELLOW | an item is open or not judged; a file could not be read or compared; a file changed that this check cannot judge and was **passed unjudged**; commits were skipped (`skip=yes`) |
| GREEN | every picked area was judged, nothing was found for the register, and nothing was passed unjudged |

The items are the areas this check ever picked. GREEN says that about them and about nothing else.

**The switch `green`.** Whether a check that selects by text should say GREEN at all is a decision for whoever
runs it. With `--input green=no` a run that would be GREEN is YELLOW, and its description begins `nothing found in
what changed (this check does not say GREEN): ...`; GREEN is then the full audit's word alone. The default is
`green=yes`. It is one place in the tool (`Report.of`, where the gate is set) and this paragraph.

**The last full audit.** The report has a row for it. A run of this audit gets the register of this audit and no
other, so the package cannot look up when `security` last ran; it prints what the operator names:
`--input full_audit=<commit>` gives `last full audit: <commit>, N commits before the workspace commit`, and without
the input the row says `not given`. The commit is the one the workspace was at when the full audit last ran. A
run started with `--source` records it (`workspace_source_sha` in `run.started`, docs/events.md); for a run on a
workspace of your own, note the commit the workspace is at when you start the full audit: the record of such a run
does not hold it.

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

So the register has no item that no run comes back to: an item is settled, or the next run judges it. What is no
item (a file that was not read, a changed file that was not compared) is not in the register; it holds the base
instead ("When the base moves"), so the next run meets it again.

Decisions do not travel between the two registers. A finding dismissed in `security` is a new finding here when
this check reports it, and the other way round.

## What it needs of git

The `base` and `blobs` steps run `scripts/changes.sh` in the built-in shell image, which has git and bash. They
need no network and write nothing into the workspace, which every container sees read-only. The area tool starts
no git; it reads what the steps wrote.

The script runs these git commands and no other, each with `--no-pager`, `--no-replace-objects`,
`GIT_OPTIONAL_LOCKS=0`, `GIT_CONFIG_NOSYSTEM=1` and `GIT_TERMINAL_PROMPT=0`:

| Command | What it reads | Why the workspace cannot make it start a program |
|---|---|---|
| `rev-parse --is-inside-work-tree`, `rev-parse --verify <name>^{commit}` | the repository's layout, a reference, an object | it resolves names; `since` is refused when it starts with `-` and stands after `--end-of-options` |
| `config --get-regexp` | `.git/config` | it prints values and runs none |
| `rev-list --first-parent --format`, `rev-list --count`, `rev-list --no-walk --format`, `merge-base --is-ancestor`, `cat-file -e` | commit objects | they walk the history; replace references are off, so a commit is the commit its id names |
| `ls-tree -r -z <commit>` | tree objects | it lists a tree; it does not read the index or the files |
| `cat-file --batch` | blob objects, by the ids on standard input | it prints bytes; each line of the input is checked to be 40 or 64 hex digits, so it names an object and nothing else; text conversion and filters need options that are not given |

No command is given a path of the workspace, and none reads a file of the workspace: what git sees of the
workspace's content is nothing. None reads or writes the index, so no index bit counts and no `fsmonitor` or
`post-index-change` hook is asked. None runs a diff, so no external diff, text conversion or attribute counts.
None updates a reference or checks a file out, so no hook runs. None talks to a remote. **A partial clone is
refused** (`extensions.partialclone`, a `promisor` remote): reading an object it does not have would start a fetch.
**A workspace with a submodule is refused** by the tool, which reads the trees: the check does not look into one.

What was tried, with git 2.43 on the host (`ChangesScriptTest`): a repository whose configuration names a program
for `diff.external`, `core.fsmonitor`, `core.pager`, a `clean`, `smudge` and `process` filter, `textconv`,
`core.sshCommand`, `core.alternateRefsCommand`, `core.editor`, `credential.helper` and
`uploadpack.packObjectsHook`, with those hooks in `.git/hooks` and the filter and diff driver set for `*.java` in
`.gitattributes`: the program did not run. A file under `-diff`, `binary` and `core.bigFileThreshold=1`, a file
with `skip-worktree` or `assume-unchanged`, an ignored new file, a file moved below a directory called `build`,
a path that starts with a quote (beside a decoy of the unquoted name with the old bytes) and a path with a line
break were each found as what they are; a replace reference did not move the base; a list of blobs that holds a
path or an option is refused. Not tried: other versions of git, and the steps in a container.

- **The workspace must hold its history**: a checkout with its `.git` directory. A linked worktree (`git worktree
  add`) has a `.git` file that points outside the workspace, which the container does not see; the step fails and
  says so. Run from the main checkout, or with `--source`.
- **`--source` works**: the checkout of such a run is a full clone of the source, without a depth limit, so the
  base is there as long as a branch or tag of the source reaches it. Such a workspace has no uncommitted changes.
- A base that the checkout does not have (the last run was in another clone, or the commit was rewritten) fails
  the step with the commit named; give `since`.
- For a date the script needs the `date -d` of GNU coreutils; the shell image has it. A commit id has 40 hex
  digits, or 64 in a SHA-256 repository.
- "Uncommitted" in the report means that the workspace differs from its commit by content. A `.java` file git
  ignores counts, so a workspace `git status` calls clean can be reported as holding changes.

## What it does not see

- **A change two calls away.** When `a` calls `b` and `b` calls the changed `c`, the area of `a` is not looked at.
- **What is no call.** A changed field, constant or initializer picks every area of its own file (reason
  `changed: a field of its file`): the members of a class read it without a call. A reader in another file is not
  picked. And a call the compiler could not resolve: on a library type, or through
  reflection.
- **A base that was never audited.** The check judges the changes; it takes the base as it is. Run the long audit
  once, or accept that the code before the base is unjudged. A `since` later than the last run's commit is
  refused, unless `skip=yes` says that the commits between the two shall stay unjudged.
- **Changes outside the Java sources.** A changed rule file, workflow, Dockerfile or `pom.xml` maps to no area. The
  report names such files, and the gate is not GREEN in that run. Tests and documentation are counted only.
- **Renamed members.** An item and a finding are known by the file and the member. After a rename the area is a
  new item, the old item leaves the register, and an open finding of the old name is named as gone and comes back
  as new if it still holds, without the decision a person made on the old one.
- **Removed code.** A removed file is counted and has no lines. Where lines were removed inside a member, the
  lines around the gap count as changed. The calls are those of the sources as they are now, so a removed member
  has no neighbours: when an overriding method is removed, its callers still compile, now reach the inherited
  method, and are not looked at unless they changed themselves.
- **New files that are no Java file, in no commit.** A new file of any kind that the workspace commit holds is
  counted; one that is in no commit is seen only when it is a `.java` file, since nothing else tells a new source
  from the output of a build.
- **A member that is gone with its file.** A finding whose member is no area any more is named as gone by the
  tool, without a model: the code it quoted is not in the compiled sources. The exception is above (Java code
  changed or came where the finder does not read). A member moved to another class of the compiled sources is a
  new area, and a defect that moved with it is found again only when that area is picked and judged; it is
  picked, since its lines are new.
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
(`CallsTest`), the comparison by content, the lines and the picks (`ChangesTest`), the tool and the report
(`ChangesMainTest`), the script and the tool against real git repositories, hostile ones among them
(`ChangesScriptTest`; it needs bash and git and fails without them), the package as it ships (`ChangesPackageTest`), and the
package through the engine into its register across runs with different bases, with a shell loop in the model's
place (`ChangesRoundTripTest`). `SecurityChangesToolIT` in `integration-tests` runs the script and the area tool
in their images on a small repository; it is written and was not run here. `SecurityChangesScriptIT` in `integration-tests` runs the script in the shell image
(`mvn -Pit`); it is written and was not run.
