# Architecture self-audit

Traceworks reviews its own architecture against [docs/architecture.md](../../../docs/architecture.md). A deterministic
tool measures what can be measured and decides *which* files are judged; an AI judges only whether those files'
responsibilities sit together well; a tool checks the AI's evidence and sets the gate.

```
facts     (shell, ./images/architecture)  measures the sources, chooses the candidates -> architecture-facts.json, candidates.jsonl
verdicts  (foreach, Claude, no tools)     one verdict per candidate: fine, split or move
report    (shell, ./images/architecture)  checks the evidence, writes the report       -> architecture-report.md, the gate
```

Why it is built this way:

- **Measured findings need no AI.** A package cycle, a layer violation and direct I/O that the rule file does not allow
  are facts of the sources. The report lists them from `architecture-facts.json`, also when every AI call fails, and
  the gate follows from them; no model text reaches a gate line.
- **Coverage is the tool's decision, not the model's.** The tool chooses the candidates by fixed rules (below), names
  the ones the cap cuts, and the record holds `candidates.jsonl`, so you can see what was judged.
- **A run judges what needs a verdict.** Every candidate file is an item of the audit. The `facts` step gets
  `{{ audit_items }}` and leaves out a file that was judged, is settled and has not changed since ("The register",
  below).
- **The AI needs no tools.** Each prompt holds the file's numbered code, the lines of the measured findings that name
  it, and a context the tool computes from the dependency graph: the types that use the file and what they use of it,
  the types it uses and through which members, each with the first sentence of its Javadoc, and every package with
  the first sentence of its `package-info.java`. That is what a reviewer with `tools: read` would look up first, and
  the tool looks it up exactly, by resolved type. So the call runs with `tools: none`: it cannot read anything else,
  and it cannot change anything. An `agentic` step with `tools: read` was the alternative; it would choose its own
  reading (coverage no longer in the record), cost more calls, and widen what audited text can steer. Where the
  computed context is not enough, see "Limits".
- **A verdict counts only with evidence.** It quotes one to three lines of the code it saw; a `split` names members
  that exist in the file and a target; a `move` names a target; both say how much they matter (high, medium or low);
  for a file with a measured finding it also says what the dependency or the I/O is for and the smallest change that
  removes it. The report checks all of it and lists
  every other verdict as unverified.
- **An answer cannot speak for another candidate.** Each line of `candidates.jsonl` carries a random token of the run
  that no prompt shows, so no model can write the marker of another candidate's answer; a marker that occurs twice
  leaves its candidate not judged. The findings, the context and the code sit between lines with a random frame of the
  run, which the audited text cannot close. The `verdicts` step declares `untrusted_input`, so an instruction aimed at
  the model in the sources turns the gate YELLOW.
- **No model text reaches a gate line.** The report step prints its gate first, shows a verdict on standard output
  only as one of three words, and escapes every name from the workspace on standard output and in the report.

## Run it

From the root of a Traceworks checkout, which is the workspace the audit reads:

```bash
traceworks images build examples/self-audit/architecture    # once: the shell, claude and architecture images
traceworks check examples/self-audit/architecture
traceworks run examples/self-audit/architecture
```

The `verdicts` step calls Claude, so it needs your Claude credentials (see "AI steps" in
[docs/cli.md](../../../docs/cli.md)). **It makes one call per candidate that needs a verdict**, one after the other:
21 calls on Traceworks on 2026-11-11, when every run judged every candidate. A later run judges the files that
changed or have an open verdict. The model is pinned (`claude-opus-5-5`), so a later run judges with the
same model; `traceworks check` notes that as `dated-model-id`. Change it in `workflow.json` when the model is retired.

The run leaves its results in the scratch directory, and the record keeps them:

| File | What it holds |
|---|---|
| `architecture-facts.json` | the source roots, files read and not read, packages, package dependencies (with the types that make them), cycles, layer violations and direct I/O (with their lines), the files with their sizes, the most complex members, the candidates of this run and the ones cut, and under `items` every candidate file with its item name, its digest, the state the register gave it, whether this run chose it and whether it was left out as settled |
| `candidates.jsonl` | one candidate of this run per line: file, its item name and digest, why it was chosen, its findings, the computed context, the numbered code the AI saw, its members, and the run's token and frame (so the file differs from run to run in these two fields) |
| `architecture-report.md` | the gate, the coverage, the measured findings, the verified verdicts, the unverified ones, every gap, the documented I/O, and the largest files, the most complex members and the package dependencies |
| `findings.jsonl` | the findings of the envelope as data, one JSON object each: the places of a measured finding, and for a verdict the item of its file (`item`), the number the candidate has in this run (`number`), the quoted lines, the members and the model's reason and target. `traceworks findings architecture show <id>` reads it from the record of the run ([docs/registers.md](../../../docs/registers.md), "The detail of a finding") |

**The gate follows the items.** Every candidate file is an item, and the report step says in what state each is
after the run:

| Gate | When |
|---|---|
| RED | a layer violation or direct I/O the rule file does not allow; or an open verdict of high severity stands: a `split` or `move` rated high, or an open high one of the register that this run did not answer, also when its file got no place or its call failed |
| YELLOW | a package cycle; an item is open or not judged; a candidate shown in part, in the run that judges it and in every later run that leaves it out as settled; findings of the register that the run was not shown; a file not read; a type name that did not resolve but names a type of the sources; or no Java file read at all |
| GREEN | every item is settled, and nothing above |

| An item is | When |
|---|---|
| clean | `facts` left it out as clean; or its verdict holds and is `fine`, or a `split` or `move` rated low |
| decided | `facts` left it out as decided; or its verdict holds with nothing for the register, and a person decided a verdict of it or this run's verdict says that an open one is gone |
| open | a `split` or `move` of medium or high, an open verdict of the register that is given again, or one that got no answer |
| not judged | no place under the cap, a failed call, or a verdict whose evidence does not hold |

The gate description ends with the number that can reach its goal, in the same words in every self-audit:
`87 of 110 items settled (71 clean, 16 decided; 20 open, 3 not judged)`. The report has the same line. Where nothing
sets `{{ audit_items }}` and `{{ audit_findings }}`, nothing was known before and the same holds with what this run
judged. On Traceworks the
tool measures no finding today (no cycle, no layer violation, no forbidden
I/O), so the gate follows from the verdicts. The one cycle it found, `cli` and `cli.command` (`cli.Main` created every
command, and the commands use the toolkit in `cli`), was removed by moving the entry point to `cli.main`; the rules
`cli_toolkit` and `cli_main_on_top` keep it so.

## The register

The workflow is an audit (`metadata.audit`, key `architecture`): the `report` step ends its output with the audit
result envelope, and the register `architecture` under the home keeps the findings across runs
([docs/registers.md](../../../docs/registers.md)). The envelope carries every measured finding (`layer-violation`,
`forbidden-io`, `package-cycle`, severity HIGH) and every verified `split` or `move` verdict rated medium or high
(`verdict-split`, `verdict-move`, with that severity, the file as subject, the quoted lines as evidence); a `fine`
verdict is no finding.
No text of a model is in it: a verdict's reason and target stay in `architecture-report.md` and `findings.jsonl`.
The envelope is a full snapshot unless a file could not be read. Then it says `"snapshotComplete": false`, the
register rejects it (`report.rejected`) and no finding changes: a measured finding that nobody looked for must not
count as resolved. A candidate without a verdict does not make the snapshot partial (below). `catalog.json` names
the five rules and their severities, and marks `verdict-split` and `verdict-move` as judged by a model
(`"judged": true`).

**The candidate files are items, and a run judges only what needs a verdict.** Every file the tool would have an AI
judge is an item of the audit ([docs/registers.md](../../../docs/registers.md), "Items"), named by its path below
the workspace, which is also the subject of a verdict about it. `facts` gives each a digest of what a verdict about
it sees: `--verdict-version`, and what the prompt shows of the file: its sizes, why it was chosen, the measured
findings that name it, the computed context, and the code with what is said about the part shown. The envelope
lists every item, with `judged: true` for a file whose verdict holds, and every `split` or `move` names the item of
its file. The register keeps the digest and the run of each item's last verdict and hands them to the next run as
`{{ audit_items }}`:

| What the register says of the file | What the next run does |
|---|---|
| `clean` (judged as fine) or `decided` (every verdict accepted, dismissed or resolved), and the digest is the same | nothing: the file is not sent to the model |
| `not judged`, or unknown, or the digest differs: the file or what the tool measured about it changed | judges it, in the order of "How the candidates are chosen" |
| `open` and the digest is the same, or an open verdict of the file is in `{{ audit_findings }}` | asks again, in the places the files above leave (`--max-candidates`) |

So the cap counts the files of one run, not the files there are: the ones that are settled free their places, and
over some runs every candidate is judged once. `traceworks findings architecture` shows how many items are settled,
and `traceworks findings architecture items` lists them. Raise `--verdict-version` in `workflow.json` when the
prompt or the instructions change what is asked, and every file is judged again. Where nothing sets
`{{ audit_items }}` (an older Traceworks), every candidate is judged, as before.

The digest holds the context too, so a file is judged again when a type that uses it gets another first Javadoc
sentence, or when a package is new or gone: a `move` names a package. It leaves out one thing the prompt shows, the
number of types of each package, so a class added anywhere does not make every file be judged again ("Limits").

**Low verdicts stay in the report of the run.** The instructions say what a severity means here: high when the
place of the code makes a rule of `docs/architecture.md` untrue or a guarantee fragile; medium when two
responsibilities that change for different reasons share the file, or a mechanism exists twice; low when the file
would read better after the change and nothing relies on it. The register and the gate take medium and high. A
`split` or `move` the verdict rates low passes the same check and is listed in `architecture-report.md` under "Low,
in this report only", and nowhere else: not in the envelope, not in `findings.jsonl`. Its file counts as judged, and
can be clean. An open verdict the register already holds is answered as before: the same verdict again states it,
whatever its severity, and another verdict that holds, also a low one, says that it is gone.

**One failed call is one file not judged.** A call that timed out, failed or answered without JSON, and a file that
got no place under the cap, leave their item with `judged: false`. So does a verdict whose evidence does not hold:
the register never sees such a verdict, and the item must not read clean because of it. The register applies the
report, the item keeps the verdict it had, and its open verdicts stay open, since no run said that they are gone.
The gate stays YELLOW, and the next run judges the file again.

**A verdict is not resolved by silence.** The three measured rules resolve at once when a snapshot no longer states
them. A verdict is a judgement, and the register keeps it open until a run says explicitly that it is gone
([docs/registers.md](../../../docs/registers.md), "Reconciliation"). The `facts` step gets `{{ audit_findings }}`,
and `facts --known` sorts it to the candidate files: each line of `candidates.jsonl` carries the open verdicts of
its own file and the ones a person decided, and the prompt holds them between frame lines. The verdict of the run
answers every open one:

| The verdict of this run | What the report step does | In the register |
|---|---|---|
| the same verdict again, with evidence that holds | states the finding again | seen, stays `OPEN` |
| another verdict (`fine`, or `move` for an open `split`), with evidence that holds | names the earlier one in the envelope's `gone` | `RESOLVED` |
| a verdict whose evidence does not hold | lists the earlier one under "Open verdicts without an answer" | stays `OPEN`, counted as not mentioned |

An open verdict of a file that is no candidate any more, and was not cut by the cap, is named as gone by the tool:
nothing measured picks the file. One of a file the cap cut stays open: nobody looked at it.

```bash
traceworks findings architecture list
traceworks findings architecture accept <id> --note "a fold over every event type stays one"
```

## What the tool measures

The `architecture` tool (`images/architecture/src`, JDK only) reads every `src/main/java` up to six levels below the
workspace (not below `target`, `build`, `node_modules` or a hidden directory) in one `javac` task and attributes it.
The class path is empty: the sources resolve against the platform and each other only, which is all the dependency
graph needs. It runs no Maven, no git and nothing from the network.

- **Dependencies.** Every reference from one top-level type to another type of the sources counts, by the element it
  resolves to: a call, a field, a type in a signature, a constructor, a method reference, and a member used on a value
  of a source type (`view.status().name()` uses `RunStatus`, though `name()` is `Enum`'s). An import alone does not
  count. A nested type counts for its top-level type. The package graph is made of these; its cycles are its strongly
  connected sets of more than one package, each with the edge that the fewest types make (the cheapest one to cut).
- **Layers.** A `layer` rule says which packages must not depend on which; each forbidden edge is a violation, with
  the types that make it and their lines.
- **Direct I/O.** An `io` rule names types (any reference counts) and calls (on the type or a subtype) that are I/O,
  the packages where they are forbidden, and the documented exceptions by class.
- **Sizes.** Non-comment lines per file: the lines that hold anything besides blanks and comments, read with a small
  lexer, so comment markers in strings and text blocks are code.
- **Complexity.** A member's cognitive complexity, after G. Ann Campbell's definition (the one Sonar and PMD use),
  from the syntax tree: each `if`, `?:`, `switch`, loop and `catch` adds 1 plus its nesting depth; an `else` or
  `else if` adds 1; each run of one logical operator (`&&` or `||`) adds 1, so each change of operator adds 1 again; a
  `break` or `continue` to a label adds 1. The depth grows inside those structures (an `else if` stays at the depth of
  its `if`) and inside a lambda or a local or anonymous class, whose code counts toward the member that holds it.
  Recursion is not counted.

A file that does not parse, a second copy of a class, a symbolic link (to a file or a directory) and a file that is no
UTF-8 are named as not read and keep the gate from GREEN. So does a type name that does not resolve but is the simple
name of a type of the sources: a dependency the graph may miss.

## How the candidates are chosen

In this order, each file once with all its reasons. A run judges at most 24 of them (`--max-candidates`): the ones
that need a verdict ("The register"), in this order; the rest of those are named as cut and wait for a later run:

1. every file a measured finding names: the types of a layer violation, a type with forbidden I/O, and for a cycle the
   types of its edge with the fewest types;
2. the files of at least 300 non-comment lines (`--large-ncloc`), largest first;
3. the files with a member of cognitive complexity 30 or more (`--complex-member`), most complex first.

A file of at most 800 lines (`--max-code-lines`) is shown whole. A longer one is shown in part: its header, then whole
members until the budget is spent (first those that hold a line of a measured finding, then the most complex, then the
rest from the top), with an outline that names every member and its lines; the report names the candidate as shown in
part. Its verdict saw a part of the file, and the item's digest holds what the prompt showed. So the gate is YELLOW
over such a file for as long as it is longer than the prompt holds: in the run that judges it, and in the later runs
that leave it out as settled, which list it under a heading of their own. Raise `--max-code-lines`, or split the file. The two lists of the context stop at 6000 characters each (half of `--max-context-chars`) and name the types they
left out; the list of packages comes on top. On Traceworks every candidate was shown whole, and the two largest
contexts (`ExecutionContext`, used by 41 types, and `Resuming`) were cut.

## The rule file

`rules/architecture.rules` holds the layer rules and the I/O rule, filled from docs/architecture.md and from the layer
rules of an earlier architecture review. A violation is a measured finding and turns the gate RED.

```
prefix traceworks

layer inner_core
  why  The inner packages of the core know nothing of the packages that use them.
  from core.port core.model
  not  core.app core.blocks.*

io core_io
  why   The core reaches files, processes and the network through its ports.
  in    core.*
  type  java.nio.file.Files java.net.http.*
  call  java.lang.System#getenv
  allow core.app.TraceworksVersion reads the version from the class path
```

| Line | Meaning |
|---|---|
| `prefix <package>` | names below it are written without it; once, before the first rule |
| `layer <id>`, `io <id>` | starts a rule; the id matches `[a-z][a-z0-9_]*` and is unique |
| `why <text>` | what the rule protects; once per rule |
| `from <pattern>...` | (layer) the packages the rule constrains |
| `not <pattern>...` | (layer) the packages they must not depend on |
| `in <pattern>...` | (io) the packages where the I/O is forbidden |
| `type <type or pattern>...` | (io) a reference to this type, or to a type of this package and below for `<package>.*` |
| `call <type>#<method>` | (io) a call or method reference of the method on the type or a subtype |
| `allow <class> <reason>` | (io) a documented exception: the class, or `<package>.*`, and why it may |

A package pattern ending in `.*` matches that package and every package below it; any other pattern matches one
package. Blank lines and lines starting with `#` are ignored; an error is refused with its line number, and so is a
`call` that names a method its type (when the type is in the platform or the sources) does not have. The report notes
an `allow` that matches no I/O and a pattern that matches no package, so a rule file that has gone stale shows.

## Limits

- **Not measured in this version:** churn and hotspots from git history, and duplicated code (what PMD CPD found in
  an earlier review). The tool reads no git, so a large file that has stopped changing looks the same as one that
  changes every week.
- **A verdict cannot see a sequence repeated in another file, or a mechanism that already exists elsewhere.** The
  context shows the neighbours by their description, not their code. That is enough to judge the file's own
  responsibilities, and for a `split` all its members are in the prompt. But when a method of one file runs the same
  steps as a method of another (an earlier review found `RunServices.start` repeating the run execution of
  `Resuming.resume`), the model sees only that the one file uses the other, not the repeated lines; and a `move`
  towards an existing mechanism rests on the one-line descriptions alone. A duplicate finder that puts the other
  place's lines into the context would close this gap; this version has none.
- **A verdict is not asked for again because a package grew.** The prompt shows how many types each package has,
  and the digest of a file leaves these counts out. A file judged as fine stays settled when the package a `move`
  would have named has doubled since. Raise `--verdict-version` in `workflow.json` to have every file judged again.
- **The severity is the model's word.** A model that rates everything low hides nothing from the report of the run,
  but keeps it out of the register and away from the gate. Read the low verdicts of a run when their share looks
  wrong.
- **An AI that misses a mixed responsibility misses it;** the evidence check only removes verdicts that do not hold.
- **Sources, not bytecode.** A constant from another class counts as a dependency here (`javac` inlines it, so `jdeps`
  misses it); otherwise the package graph on Traceworks matches `jdeps` exactly.
- The rule file names Traceworks' packages. On another Java project, run it with `--workspace` and write its own rule
  file.

## Tests

The tool's tests are in [`examples/self-audit/tests-architecture`](../tests-architecture), a module of the Maven build,
so `mvn verify` runs them; they make no AI call. One of them runs the package's shape through the real engine as a host
run (it needs `bash`); on its own, run the module with `-am`: `mvn -pl examples/self-audit/tests-architecture -am test`.
