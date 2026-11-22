# Registers: findings across runs

A run is a record of one execution. A **register** is what an audit knows across its runs on one project: the
findings its reports stated, which of them are new, still open, resolved, reopened or decided by a person, and when
each was seen. It
is kept with the same means a run uses: an append-only, hash-chained log of plain
files, blobs, and derived files a reader can check against the log. There is no database and no service.

A register has three readers: the next run of the audit on the same project, which gets what the register knows as
untrusted data
(`{{ audit_findings }}`, `{{ previous_report }}`, and without their caps as files, `{{ register_dir }}`); a person, through `traceworks findings` and `report.html`; and the
operator's hooks, which get the diff of each run (a TODO tool, an issue tracker).

## The home, the ledger and the registers

![A map of a Traceworks home. On the left the operator, who starts a run of a package with a project key, the package, the project's workspace, and a person who decides on findings. In the middle the record of the run: plain files with a hash chain from run.started to run.finished, blobs, manifest.json and run.html. On the right the ledger, the one git repository, with a start entry and an end entry per run, the acknowledgements, and a file of anchors per register key and project. Below, the registers of one workflow key with a compartment per project: events.jsonl, blobs/, state.json and report.html. Arrows: the hash of line 0 to the ledger before the first step; the manifest with the head when the run ends; the report of an audit run into the register of its project; the known findings back to the next run of that project only; a person's decision into the register; and after every write an anchor into the ledger with the sequence number, the head, what was written and by whom.](ledger-and-registers.svg)

Three things of a home (docs/cli.md, "Home") are easy to take for one another:

- **The ledger is one per home**, for all runs of all projects and all workflows. It is the only git repository
  Traceworks keeps. It says which runs began and ended, on which project and started by whom, and where the log of
  each register stood after every write (docs/events.md, "The ledger").
- **A register is per workflow key and per project.** The key is the audit's (`metadata.audit.key`, below); the
  project is the operator's word for what a run works on ("The project of a run"). One audit package run on two
  projects feeds two registers, and a run sees only the register of its own project.
- **A run folder and a register are plain files, not git.** Each is a log with a hash chain, blobs named by their
  SHA-256, and files derived from the log. The ledger holds the head of each chain: of a run in its end entry, of a
  register in its anchors. It holds no content of a register: no title, subject, note, severity or evidence, only
  what an anchor says ("The anchor").

## An audit workflow

A workflow feeds a register when its `metadata.audit` says so:

```json
"metadata": {
  "audit": {"enabled": true, "key": "architecture", "category": "architecture", "report_block": "report"}
}
```

| Field | Meaning |
|---|---|
| `enabled` | `true` to feed a register; `false` or absent is an ordinary workflow |
| `key` | the register, `[a-z0-9][a-z0-9._-]{0,127}`; with the project of the run it names one register ("The project of a run"). A key belongs to one workflow (by its `id`) across all its projects: the workflow whose run created a register of the key. A run of another workflow with the same key is refused at its start and writes nothing |
| `category` | a word for the register's page and `findings` |
| `report_block` | the name of the top-level block whose output is the report, with the envelope at its end |
| `schema_version` | optional, `1` |

`traceworks check` refuses an audit configuration it does not understand (`invalid-audit-config`,
`invalid-audit-key`, `report-block-missing`, `catalog-invalid`), and so does the start of a run (docs/cli.md,
"check"). A silently ignored audit would stop feeding its register, which is worse than a refused run.

The report block's output ends with the **audit result envelope**, a full snapshot of the findings:

```
<!-- traceworks:audit-result:v1 {"snapshotComplete": true, "expectedFindings": 2, "findings": [
  {"ruleKey": "long-file", "subject": "a.txt", "severity": "LOW", "title": "a.txt has 12 lines, more than 10",
   "description": "...", "evidence": "long_file: a.txt (12 lines)", "tags": []},
  ...]} -->
```

| Field | Meaning |
|---|---|
| `snapshotComplete` | must be the boolean `true`: the envelope names every finding the audit knows. A partial snapshot (`false`) is rejected, because applying it would resolve findings the audit never checked. An audit that lists its `items` stays complete when a verdict is missing: the item says so (below, "Items") |
| `expectedFindings` | optional; when given, it must equal the number of findings |
| `findings[].ruleKey` | the rule, such as `layer-violation`; with `subject` it is the identity of the finding |
| `findings[].subject` | what the finding is about: a file, a type, a dependency |
| `findings[].severity` | `CRITICAL`, `HIGH`, `MEDIUM` or `LOW` |
| `findings[].title` | one line |
| `findings[].description`, `evidence`, `tags` | optional. The evidence names a fact of the run (below) |
| `gone[]` | optional, a Traceworks field: the findings the run checked and found gone, each `{"ruleKey", "subject"}` (more fields, such as a reason, are not read). It matters for a rule a model judges (below, "catalog.json" and "Reconciliation"); at most 1000 |
| `items[]` | optional, a Traceworks field: the items the audit judges, each `{"name", "digest", "judged"}` (below, "Items"); at most 1000 |
| `expectedItems` | optional, a Traceworks field; when given, it must equal the number of items, and the envelope then vouches for the whole list |
| `findings[].item` | optional, a Traceworks field: the name of the item the finding is about; with `items` given it must be one of them |

The last readable envelope of the output counts. The register reads it strictly: a wrong type, a missing field, a
severity outside the four, more than 1000 findings or a field over its limit is an error, and every error is
reported, not the first (docs/workflow-format.md). A report without an envelope may state its findings as
`todo_item:` lines instead, `todo_item: <title> | <PRIORITY> | <tag,tag> | [description]`, each a finding of the
rule `todo-item` whose subject is its title; a reworded title is a new finding. A line has the limits of a finding
of an envelope: a title over 2000 or a description over 20 000 characters rejects the report. That is the simple form for a workflow whose findings are tasks (GitHub issues, Jira), not measurements.

`examples/audit-hello` is the smallest audit: three shell steps and no AI. `examples/self-audit/architecture` feeds
the register `architecture` with its measured findings and its verified verdicts. `examples/self-audit/security`
feeds a register of its own; `examples/self-audit/README.md` shows how the audits, the registers and the triage of
their findings make a loop. `examples/self-audit/security-changes` is an audit whose
runs each judge a part of the project (what changed since a commit): it lists every item its register knows in
every report, and takes every item that is open or was not judged into its next run.

`examples/mutation-testing` is an audit of the tests of a Maven project: it measures with mutation testing (PIT)
which changes of the code no test notices, has a model rate each method's gap, and feeds the register
`mutation-testing`. It reads what the register knew from the register folder of the run (`{{ register_dir }}`, see
"The register folder of a run"). `examples/mutation-testing-grounded` measures the same, reads the register folder the
same way, and rates a changed method from its earlier verdict, moving a rating only on a ground a script finds; it
feeds the register `mutation-testing-grounded`.

### catalog.json

A package may carry a `catalog.json` beside `workflow.json`:

```json
{"format": 1, "rules": {"long-file": {"severity": {"min": "LOW", "max": "MEDIUM"}, "subject": "a fixed subject"},
                        "verdict-split": {"judged": true}}}
```

With a catalog, a report that states a rule the catalog does not know, or a severity outside a rule's range, is
rejected as a whole (`report.rejected`, with every problem named): the audit and its catalog disagree, and a
register must not guess. A rule may fix its subject, so every finding of the rule is one finding (a metric).
A rule may be marked `"judged": true`: its findings are judgements of a model, not measurements, and silence does
not resolve them (below, "Reconciliation"). A rule that is not marked is measured, so a catalog without the key
means what it meant before. A `gone` entry of a rule the catalog does not know rejects the report like a finding
of such a rule.
`{{ audit_findings }}` shows only the findings of catalogued rules when a catalog exists, and an accepted or
dismissed finding whose rule left the catalog is resolved. Without a catalog, every rule key counts as it is. A
catalog that cannot be read refuses the run. The catalog a report was applied with is kept in the register
(`catalog_blob` of `report.applied`), so a replay of the log applies each report as it was applied, whatever the
package's catalog says today.

### Choosing the subject

The register does not know what a subject is: it is the text the audit writes, and with the rule it is the whole
identity of a finding. The audit therefore decides how fine its findings are: a file, a class, `Class#method`, a
dependency, a section of a document. Four things follow from the choice.

- **The subject is what a person decides on.** An accept or a dismiss covers everything a later report states under
  the same rule and subject. With a class as the subject, one decision covers every place in the class; with a
  method, only that method.
- **Coarse hides, fine floods.** Under a coarse subject something new arrives below a decision somebody made about
  something else, and only a higher severity opens it again ("What a decision covers"). A fine subject makes many
  findings: a report holds at most 1000, and the next run is handed at most 250 rows of `{{ audit_findings }}`
  (all of them as a file: "The register folder of a run").
  Choose the finest subject a person would still decide on one by one.
- **The subject must stay the same while the thing does.** Nothing is normalised, so a subject that holds a line
  number, a count or a sentence a model wrote is a new finding after every change, and the decision made on the old
  one no longer applies. Names are stable (a path, a type, a method, a coordinate); what moves belongs in `title` and
  `evidence`.
- **Another level is a new start.** An audit that changes its subjects, from classes to methods, say, reports
  findings the register has never seen: the old ones resolve (those of a judged rule when the report names them as
  `gone`), and the new ones come without the decisions made on the old.

## The project of a run

An audit package is written once and run on many things: a shop, a blog, this repository. The findings of one say
nothing about another, and a decision a person made for one must not cover the other. So a register is that of a key
**and a project**, and every run of an audit workflow names its project.

```bash
traceworks run examples/audit-hello --workspace ~/work/shop --project shop
#   register hello of shop: 1 new, 0 resolved, 0 reopened, 1 open now
traceworks run examples/audit-hello --workspace ~/work/blog --project blog
#   register hello of blog: 1 new, 0 resolved, 0 reopened, 1 open now
```

- **The key.** A project key matches `[a-z0-9][a-z0-9._-]{0,127}`, the pattern of a register key. Another shape is
  a usage error (exit 2) on the command line, and refuses `traceworks.json` where the setting holds it (exit 6).
- **It is the operator's word.** The key comes from the command line (`run --project KEY`) or from the home's
  `traceworks.json`, where `projects` binds a key to a folder so that the folder a run works in names its project
  (below, "The projects of a home"). It comes from nothing a workspace or a package holds: a repository that could
  name its own project could take over the decisions made for another. Nothing checks that the content of the
  workspace is what the key says ("What a register does not do"). A client of the MCP server names no project:
  what it does there is for the project `projects` binds to the folder the server works in, and for no other
  (docs/cli.md, "mcp").
- **An audit run without a project is refused** before the run exists (exit 6): no run folder, no ledger entry and
  no register folder is written. There is no default project, which would be the same trap as one register for
  all:

  ```
  traceworks: The run is refused: the workflow feeds the register hello, and a register is kept per project: name
  the project this run works on with --project KEY, or bind a key to the folder the run works in with this line in
  traceworks.json of the home: "projects": {"KEY": "/the/folder"}
  ```

- **A run of a workflow that is no audit may name a project.** It is recorded and changes nothing else.
- **The record names it.** `run.started` holds `"project": "<key>"` when the run names one, and the entries of the
  ledger for the run name it too (docs/events.md, "run.started" and "The start entry"). `show` and `run.html` have
  a line `Project`.
- **A resume keeps it.** `traceworks resume` takes no `--project`: the resumed run reads and feeds the register of
  the project its `run.started` names. An audit run whose record names no project (a run an earlier version
  started, parked or interrupted since) cannot be resumed: its report could feed no register, so the resume is
  refused (exit 6) before a step runs (`Run <run> cannot resume: the record of the run names no project, and the
  workflow feeds the register hello, which is kept per project: ...`). Start it again with `--project KEY`.

What a run gets from a register, it gets from the register of its project alone: `{{ audit_findings }}`,
`{{ previous_report }}`, `{{ audit_items }}`, the register folder (`{{ register_dir }}`) and the head of the log
that `run.started` records. What it feeds at its end is the same register. The summary of the run, the page of the
register and the diff a hook gets name the project.

### The projects of a home

Naming the project on every command is easy to get wrong: the right key in the wrong folder mixes two projects. The
operator can therefore declare the projects of the home once, each bound to a folder (docs/cli.md, "The projects of
a home"):

```json
{"projects": {"shop": "/srv/shop", "blog": "~/work/blog"}}
```

- A run whose workspace is a declared folder, or lies below it, is of that project without `--project`. With
  `--source` the source repository counts when the source names a path of this machine, as a path or as a `file:`
  URL; a run from any other URL has no folder.
- A run with `--project P` in the folder of another project, or with a declared `P` in another folder than its own,
  is refused before it exists (exit 6). An undeclared key in an undeclared folder is the operator's word, as
  without the setting.
- `findings <key> <command>` without `--project` means the declared project the working directory lies in. When
  the key has no register of that project, the command is refused (exit 2) and names the projects the key has. In
  a folder of no declared project it means the only project the key has ("Reading a register").
- A key that stands twice in `projects` refuses the setting: a project is bound to one folder.
- Folders are compared by their real paths at the time they are asked. The setting is read from the home alone, and
  no command writes it. The record does not say whether the option or the setting named the project, and a resume
  keeps the project of `run.started` whatever the setting says by then.

The identity of a finding does not hold the project ("What a run does to its register", step 3): the same rule and
subject in two projects have the same id, in two registers. A decision is a line of one register and covers that
project only.

**One workflow per key, across all projects.** At its start, a run of an audit workflow reads the line every
register of its key begins with, of every project: when one of them belongs to another workflow, the run is refused
(`the register hello belongs to the workflow <id>, not to <id>: a key belongs to one workflow across all its
projects, and <id> feeds its register of shop`). The first report of a project is checked the same way before it
creates that project's register. The check reads the registers that can be read: one whose first line cannot be
read makes no claim and stops no run of another project. Two first runs of two workflows with one key that start
at the same moment are not excluded: each finds no register of the other yet.

## What a run does to its register

At the end of every run of an audit workflow, after the run's own record is complete:

1. The run must have ended `COMPLETED` or `COMPLETED_WITH_ERRORS`, and the report step must have completed with
   an output. Otherwise the register records `report.rejected` with the reason, and no finding changes. A run is
   applied once. (A run whose own record does not hold feeds nothing: its completion refuses before the register is
   touched.) Before the run exists, its start check reads the register of the run's project. It refuses the run
   (exit 6, nothing is written) for a key that belongs to another workflow; for a register whose log cannot be
   replayed or names another project than its folder, with a message that names `findings <key> verify --project
   <project>` ("A folder that was copied"); for a register from before registers had projects that nobody
   assigned yet ("A register from before projects"); and for a register the ledger anchors whose log is gone
   ("A register whose log is gone"). The feed at the end of the run refuses for the same reasons and writes
   nothing then: the register may have changed while the run ran.
2. The envelope (or the `todo_item:` lines) is read. An unreadable envelope is `report.rejected` with its errors,
   and so are `todo_item:` lines that cannot be read within 10 seconds (a report is a step's output, and a line built
   for it would otherwise keep the register busy for hours).
3. **Identity.** Every reported finding gets the id `sha256(<key>|<ruleKey>|<subject>)`, with `|` and `\` in the
   parts escaped. There is no normalisation: a reworded subject is a new finding (see
   docs/workflow-format.md). Of two findings of one identity in a report one counts: the most severe, the first among equals, so that
   nothing depends on the order a report states them in (a report applied by a version before 2026-11-14 took the
   first, and a replay folds it so). The catalog's fixed subject
   applies first, and a `¦` in a field (the masked separator of `{{ audit_findings }}`) is unmasked to `|`.
4. **Evidence.** The fact names of the run are collected: every `FACT <name>:` and `HIT <name>:` line of an output
   that holds `## FACT SHEET` (or its German form, `## FAKTENBLATT`), from every top-level step except the report block. A
   finding whose evidence names none of them is marked `unverified_evidence`; its status does not change. When no
   step measured anything, nothing is unverified. The check is mechanical: it cannot tell whether a fact that exists
   supports the conclusion.
5. **Reconciliation.** The report is a full snapshot. What that means depends on the rule. A **measured** rule
   (the default) is a tool's count: what the snapshot does not state is gone. A **judged** rule (`"judged": true`
   in `catalog.json`) is a model's judgement: a model that does not name a finding again has not said that it is
   fixed, so only an explicit answer resolves it, the envelope's `gone`.

   | Before | Stated in the report | Not stated, measured rule | Not stated, judged rule |
   |---|---|---|---|
   | — | `OPEN`, new | — | — |
   | `OPEN` | `OPEN`, seen | `RESOLVED` | named in `gone`: `RESOLVED`. Not mentioned: stays `OPEN`, counted |
   | `AWAITING_VERIFICATION` | `OPEN`, without a regression | `RESOLVED` (the fix is verified) | named in `gone`: `RESOLVED`. Not mentioned: stays, counted |
   | `RESOLVED` | `OPEN`, reopened, regression +1 | stays | stays |
   | `ACCEPTED_RISK` | stays; `OPEN` when the report states it with a severity above the one it was accepted on, or when the evidence got worse than the snapshot frozen at the acceptance (a value more than 10 % higher, a churn that reached 5 changes, a new advisory id) | stays; `RESOLVED` after three applied reports in a row without it | stays until its review date; `RESOLVED` when named in `gone` |
   | `DISMISSED` | stays; `OPEN` when the report states it with a severity above the one it was dismissed on | stays; `RESOLVED` after three applied reports in a row without it | stays; `RESOLVED` when named in `gone` |

   On every applied report, seen or not: an accepted risk whose review date has passed becomes
   `AWAITING_VERIFICATION`, and an accepted or dismissed finding whose rule left the catalog is `RESOLVED`. Every
   sighting updates the finding's severity, title, description, evidence, tags and `last_seen`, and counts an
   occurrence. A finding the report both states and names as gone is stated. A `gone` entry for a finding of a
   measured rule, or for one the register does not know, changes nothing.

   **A decision covers the severity it was made on.** The register knows a finding by its rule and its subject, and
   a subject can be coarse (a file, an area of code), so the same identity can come back as something a person never
   looked at. An accepted or dismissed finding that a report states with a higher severity than the finding had
   when the person decided is therefore `OPEN` again: `finding.reopened` with the reason
   `higher_severity: LOW → CRITICAL`, the severity decided on and the new one. It counts as reopened in the run's
   summary and in the hook's diff, and the next run is asked about it like any open finding. The decision with its
   note stays with the finding and in the log; deciding again, now on the higher severity, covers up to that one.
   An equal or a lower severity leaves the decision, and so does other evidence alone: only the severity decides
   (for an accepted risk, also the numbers of its frozen evidence, as before). The severity a decision covers never
   moves with the reports: a finding dismissed as `HIGH`, stated as `LOW` and then as `HIGH` again stays dismissed.
   With `"decision_on_higher_severity": "keep"` in `traceworks.json` the decision stays whatever the severity
   (below, "Deciding").

   "Counted" is `absent_reports` of the finding: how many applied reports in a row did not mention it. `findings
   <key> show` prints it (`Silent    the last 2 applied reports did not mention it`), and the run's summary says
   how many open findings its report was silent about. A finding of a judged rule that nothing asks about any more
   (its item is no longer judged) stays open with a growing count until a run names it as gone or a person
   dismisses it; the audits of `examples/self-audit` name a finding as gone themselves when the item it was about
   no longer exists.

   An accepted finding of a judged rule does not yet come back for review when the line it quotes changes: the
   register sees no file, and the frozen evidence is compared for numbers that got worse, not for a quoted line. It
   comes back at its review date, and is asked about from then on.

   So that a model answers, the next run has to ask: an audit hands each verdict call the open findings of the item
   it judges and wants an explicit answer per finding, still holds (with the line as it is now) or gone
   (`examples/self-audit/README.md`, "The register is the memory"). Only `OPEN` and `AWAITING_VERIFICATION` findings
   are asked about; an accepted or dismissed one is decided.
6. **Items.** When the envelope lists items, the register takes the list (below, "Items").
7. The register records `report.applied` with the reported findings, the items and the state after it as blobs, one
   line per finding that is new, resolved, reopened or due for review, and then, when the operator configured one,
   runs the hook and records `hook.ran`.
8. `state.json` and `report.html` are written again from the log.
9. The head of the log is anchored in the ledger (below, "The anchor"), after the report and again after the hook's
   line.

The run's summary ends with what happened, and names the register by its key and its project:

```
  register architecture of shop: 1 new, 3 resolved, 0 reopened, 17 open now
  register security of shop: 0 new, 2 resolved, 0 reopened, 9 open now, 3 of them not mentioned by this report
  register testing of shop: 2 new, 1 resolved, 0 reopened, 14 open now; items 87 of 110 settled, 29 judged in this run
  register architecture of shop: not applied — the report step 'report' ended FAILED
```

A failure of the register is reported there and never changes the run's exit code: the run's record is complete
before the register is touched.

## Items

A page to step through, with a drawing of the loop: [audit-loop.html](audit-loop.html). Open it in a
browser; it loads nothing from the network.

An audit that asks a model judges one thing per call: a class, an area of the code, a section of the docs. That
thing is an **item**. Findings alone cannot say "nothing was found here", so an audit that runs again would ask
about every item again, every time. An audit that lists its items lets the register remember which of them are
settled, and the next run asks only about the rest.

```
"expectedItems": 3,
"items": [
  {"name": "io.example.Reconciler", "digest": "3fa9c2d1e07b4a55", "judged": true},
  {"name": "io.example.Parser",     "digest": "91c07be2aa4d0f16", "judged": false},
  {"name": "io.example.Writer",     "digest": "0be7d4a1c3f29e88", "judged": true}]
```

| Field | Meaning |
|---|---|
| `name` | the item's name, stable across runs; one line, at most 300 characters, no control character, each name once. A `¦` is read as `\|`, in a name and in a finding's `item`, before the names are compared: two names that differ in that only are one name, and the list is refused |
| `digest` | a digest of what a verdict about the item sees (its code, the files it names, the version of the question), computed by the audit: 1 to 128 characters of letters, digits and `. _ : = + / -`. The register compares it and never computes it |
| `judged` | the JSON boolean: whether this run gave a verdict for the item. `false` for an item the run left out, and for one whose call failed |

The envelope lists **every** item of the audit, not only the ones it judged. The register keeps per item the digest
of the last list, and the digest and the run of its last verdict. Its state is derived from that and from the
findings that name the item (`findings[].item`); nothing stores it:

| State | When | What the next run does |
|---|---|---|
| `open` | a finding that names the item is `OPEN` or `AWAITING_VERIFICATION` | asks about each: holds or gone |
| `not judged` | no run judged the item, or its digest differs from the one of its last verdict | judges it |
| `decided` | judged as it is now, and every finding that names it is accepted, dismissed or resolved | nothing, until the item changes or a review date comes |
| `clean` | judged as it is now, and no finding ever named it | nothing, until the item changes |

What a list does to the register's items:

- A listed item is added, or takes the digest the list gives. `judged: true` makes this run its last verdict;
  `judged: false` leaves the verdict it had. So **a call that failed is one item not judged**, not a rejected
  report: the envelope stays `snapshotComplete: true`, the verdicts of the other items count, and since a judged
  rule is not resolved by silence, the open findings of the unjudged item stay open.
- An item the list does not hold leaves the register only when the envelope gives `expectedItems`. A list without
  it adds and updates and removes nothing: a cut list must not make the register forget.
- The findings of an item that left are not touched. They stay open until a run names them in `gone` or a person
  decides.
- A report without `items` leaves the items as they are.

A finding takes the `item` of the report that states it and keeps it when a later report states the finding without
one. A measured finding names no item.

`traceworks findings` shows, for a register with items, the number that can reach its goal (the line of a register
is its key, its project, its category and its counts):

```
testing  shop  testing  OPEN 14  DISMISSED 6  RESOLVED 71
  items 87 of 110 settled: 71 clean, 16 decided; 20 open, 3 not judged
```

`findings <key> items` lists them, what needs a look first (`--json` for a script: `name`, `state`, `digest`,
`verdict_digest`, `verdict`), and `report.html` has the same line and the list.

## What the next run gets

The next run of the audit on the same project gets three placeholders, filled from the register of that project
and of no other, as untrusted platform data, refused in a shell line and handed to a command under `values` like every
untrusted text and data, never instructions, in a prompt:

- `{{ audit_findings }}`: the open, awaiting, accepted and dismissed findings (never the resolved ones), one row
  each: `STATUS | SEVERITY | ruleKey | subject | title | Evidence: ... | Disposition: ...`, between
  `<audit_findings>` and `</audit_findings>`, with a notice that it is data. The severity and the evidence of a row
  are those of the last report. Where a decision was made on another severity, the row ends with one more cell,
  `| Decided on: LOW, not on CRITICAL`, so that nobody reads the decision as covering what it was never made on. Accepted and dismissed findings are kept
  first, so a model does not report what a person decided; at most 250 rows or 60 000 bytes, the rest counted in a
  last line `(+N more: OPEN 3 · DISMISSED 2)`. A step that sorts the rows to its items must conclude nothing from a
  row that is not there when it reads that line: an item may have open findings the block does not show
  (`examples/self-audit/README.md`, "The gate follows the items"). A `|` in a field is masked as `¦`, and the
  register unmasks it when the finding comes back.
- `{{ previous_report }}`: the text of the last *valid* report, its envelope cut and every marker defused, at most
  3000 characters.
- `{{ audit_items }}`: the items of the register, one row each: `state | verdict digest | item`, between
  `<audit_items>` and `</audit_items>`, with a notice that it is data; `-` stands for no verdict. It is meant for the
  step that chooses what to judge, not for a prompt: that step computes the digest of each item as it is now and
  leaves out an item whose row says `clean` or `decided` with the same digest. At most 60 000 bytes, the rest
  counted, the settled rows first: an item whose row is missing is judged again, which costs a call and never a
  wrong answer. A `|` in a name is masked as `¦`, and the register unmasks it where the name comes back: in
  `items` and as a finding's `item`.

Before the first applied report they read `(no known findings)`, `(no previous report)` and `(no known items)`. A
workflow without `metadata.audit` has none of them: `{{ previous_report }}` in it is refused by `traceworks check`
(`previous-report-outside-audit`), and `{{ audit_findings }}` and `{{ audit_items }}` stay the literal placeholder.
A step that reads `{{ audit_items }}` must take anything that is no row, the literal placeholder included, as "no
item is known" and judge every item: that is what it gets where nothing sets the variable, such as an older
Traceworks (docs/workflow-format.md).

### The register folder of a run

The three placeholders are capped, because each of them ends up in a shell line or a prompt. A register that knows
more than they show would lose its memory for the next run, and no placeholder carries the description of a finding.
So the run of an audit workflow also gets what its register knew as **files**, without those caps. The placeholders
and their limits stay exactly as they are; use whichever fits the step.

Before the first step, Traceworks writes the folder `register/` into the run's folder, from the same read of the
register that fills the placeholders:

| File | What it holds |
|---|---|
| `findings.jsonl` | the findings `{{ audit_findings }}` lists (open, awaiting verification, accepted, dismissed; with a catalog those of its rules), in its order, one JSON object a line. Every one of them, with its whole evidence and its description: no row is left out and no text is cut |
| `resolved.jsonl` | the resolved findings, in the same form |
| `items.jsonl` | the items of the register, one JSON object a line, what needs a look first |
| `previous-report.md` | the whole text of the last *valid* report, its envelope cut and every marker defused exactly as in `{{ previous_report }}`, without the limit of 3000 characters |

Every file is always there. Before the first applied report each of them is empty, so "nothing is known" is an
empty file, and a step never has to ask whether a list was cut: none is.

One line of `findings.jsonl` (wrapped here; in the file it is one line, its keys sorted):

```json
{"absent_reports":0,"description":"The check is lexical: a symbolic link gets past it.",
 "disposition":{"actor":"anna@laptop","at":"2026-11-12T09:30:00Z","evidence":"path_check: Mounts.java:41",
                "in_force":true,"kind":"DISMISSED","note":"not ours","severity":"LOW","until":null},
 "evidence":"path_check: Mounts.java:41 if (path.startsWith(home)) {",
 "first_seen":{"at":"2026-11-11T10:00:00Z","run_id":"c0c0f5eb-9c93-4d31-8cc5-b4172baedebd"},
 "id":"9d3692a8...","item":"core/Mounts.java#Mounts.check(Path)",
 "last_seen":{"at":"2026-11-14T08:15:00Z","run_id":"46aff422-bfd3-441a-a177-75131a9dd2e6"},
 "occurrences":3,"regressions":0,"resolved_at":null,"rule_key":"path_check","severity":"HIGH","status":"DISMISSED",
 "subject":"core/Mounts.java#Mounts.check(Path)","tags":[],"title":"A link gets past the path check",
 "unverified_evidence":false}
```

| Field | Meaning |
|---|---|
| `id`, `rule_key`, `subject` | the finding's identity, as in `findings <key> list --json` and `state.json` |
| `status` | `OPEN`, `AWAITING_VERIFICATION`, `ACCEPTED_RISK` or `DISMISSED`; in `resolved.jsonl`, `RESOLVED` |
| `severity`, `title`, `description`, `evidence`, `tags` | as the last report that stated the finding gave them. The description is the envelope's `description`, which no placeholder carries |
| `item` | the item the finding is about; the key is absent for a finding that names none |
| `first_seen`, `last_seen` | the run and the time of the first and of the last report that stated the finding |
| `occurrences`, `regressions`, `absent_reports`, `unverified_evidence`, `resolved_at` | as in `state.json` |
| `disposition` | `null` without a decision. More than `{{ audit_findings }}` shows, which has the note only: who decided and when are here too. Otherwise `kind` (`ACCEPTED_RISK` or `DISMISSED`), `note`, `actor`, `agent` (the agent that decided under the actor, by the name it gave itself; the key is absent for a decision that named none: "Who decided"), `at`, `until` (the review date of an acceptance), `evidence` (frozen at an acceptance), `severity` (the severity the decision was made on, always given; `null` only where a register of an earlier version does not know it) and `in_force` (always given: `false` for a decision that stays with a finding that is open again) |

One line of `items.jsonl`:

```json
{"digest":"3fa9c2d1e07b4a55","name":"io.example.Reconciler","state":"clean",
 "verdict":{"at":"2026-11-14T08:15:00Z","run_id":"46aff422-bfd3-441a-a177-75131a9dd2e6"},
 "verdict_digest":"3fa9c2d1e07b4a55"}
```

`state` is `open`, `not judged`, `decided` or `clean` ("Items"); `verdict_digest` and `verdict` are `null` before
any verdict.

The resolved findings have a file of their own, on purpose. A step that takes every line of `findings.jsonl` as
something the register still holds (to ask a model about it, or to leave it out of a report) stays right, and a
step that wants to know what was fixed before reads the other file. Each line is ASCII and holds no `<`: every
other character is escaped as JSON allows (`<`), so a text from a report can neither break a line nor stand in
the file as a marker; a JSON reader gets the text as it was.

Do not take `register/findings.jsonl` for the `findings.jsonl` an audit's report step writes into the scratch
directory ("The detail of a finding"). That one is the audit's own convention and holds a model's words about each
place; the register never reads it, and it is not in the register folder.

**The placeholder.** `{{ register_dir }}` is the folder as a step sees it, a path the engine sets like
`{{ scratch_dir }}` and `{{ package_dir }}`:

```json
{"type": "shell", "name": "known",
 "config": {"command": "bash \"{{ package_dir }}/scripts/known.sh\" \"{{ register_dir }}/findings.jsonl\""}}
```

In a container run it is `/register`, a **read-only** mount that every container of the run gets: a shell step, a
tool-mode step, and the commands of `run_if`, a shell condition and `items_command`. **The container of an AI step
does not get it.** What a model reads of the register is what a step puts into its prompt, never the folder. In a
host run it is the folder's own path, and a container step of a host run mounts it there, read-only. In a container
run of an audit, `/register` is reserved like `/workspace`: a step's own mount on or below it is refused. Any other
run reserves nothing there.

Only the run of an audit workflow has the folder, and no input can set the variable. `traceworks check` judges a
workflow by its own `metadata.audit`: in a workflow that feeds no register (no audit block, or `enabled: false`)
`{{ register_dir }}` is the error `register-dir-outside-audit`, and no run of it starts. The folder itself belongs
to the run, so for a child workflow the two can differ:

- A child of an audit run sees that run's folder and has the variable, whatever its own metadata say. `check` of
  the child alone still refuses the placeholder; read the folder in the audit workflow, or hand its path on under
  another name (a `foreach` `context` entry such as `"known_dir": "{{ register_dir }}"`; the name `register_dir`
  itself is reserved).
- A workflow started by a run that is no audit has no folder, also when it carries an audit block of its own. A
  step of it that names the folder fails before it runs anything: `{{ register_dir }} names the register folder of a
  run, and this run has none: ...`. The placeholder never reaches a command as text.

In the prompt of an AI step the placeholder is an error in every workflow (`register-dir-in-ai-prompt`): the
container of the call has no such folder, and in a host run the prompt would carry a path of the run folder. A run
that gets that far fails the step before the call. A `run_if` of an AI step is a shell command and may name it.

**Who can change the folder.** In a container run, no step: the mount is read-only, and that is the strong
guarantee. In a host run no mount protects the folder. The files are written without write permission, which stops
a mistake and not a step that means it. What a host run does instead is detect: before every command that gets the
folder it compares the files with the digests it recorded (below), and once a file is changed, missing or added it
starts neither that command nor any later one that gets the folder (exit code 125, `the register folder of the run
changed: findings.jsonl is not the file the run started with ...`). The report step is one of them, so the register
takes no report from such a run. Two limits of that, both of the host run only:

- The comparison comes before the command, not while it reads. A process that an earlier host step left running
  can write between the comparison and the read, and that command then reads what was written. The next
  comparison, a resume and `traceworks verify` find it; the step that read it has run.
- An AI call gets no folder and is not compared, so it still runs after a change. It reads nothing of the folder.

**What the run records.** `run.started` holds, for an audit workflow only, the field `register` (docs/events.md):
the register's key and its project, the head of the register's log the run read (`seq` and `head`, the SHA-256 of
that line; both `null` when the register did not exist yet), and the name, the SHA-256 and the size of each file. So the record
says what the run knew of earlier runs, and from which state of the register. Before this field no run recorded
the head of the register it read. The files are not copied into the run's blobs: the folder is part of the run
folder, and

- `traceworks verify <run>` compares the folder with the recorded digests and fails (exit 8) for a file that is
  changed, missing or added, also on a copied run folder;
- a resumed run goes on with the folder it was started with, whatever the register holds by then, and `resume`
  refuses, before it records anything, when the folder is no longer the recorded one;
- `show` and `run.html` name the head the run started from (`Register  hello of shop at seq 17 (head
  3fa9c2d1e07b), 4 files handed to the steps`), and `traceworks diff` shows it as a field (`register hello of
  shop`), so two runs that started from the same state of the register can be told from two that did not.

The files are made from the register's log and blobs, which the start check replays: a register that cannot be
replayed, or one with a blob the replay reads (the findings, the items or the catalog of a report, the last report's
text) that does not hash to its name, refuses the run and names `findings <key> verify --project <project>`.

**It is data, and part of it was written by a model.** Titles, subjects, evidence, descriptions and the report
come from the output of earlier runs, and notes from a person. The files also hold what the placeholders leave out:
the actor of each decision (`disposition.actor`, a name such as `anna@laptop`), the agent that decided under that
actor when it named itself (`disposition.agent`) and the decision's time, and the ids of earlier runs. Every step of the audit that gets the folder can read them, and a step that prints them puts them into the
run's record. Treat a file as the placeholders are treated: a
step that passes text from the folder to a model passes untrusted text, should set `untrusted_input: true` on that
AI step, and should quote what it puts into a shell line. Traceworks cannot follow a file the way it follows a
placeholder: a step that reads the folder and prints part of it has an output like any other. The markers in
`previous-report.md` are defused, and a line of the `.jsonl` files cannot hold one; what a step makes of the
decoded text is the step's. And a digest says that a file is the one the run was handed, not that what the
register held was right: a finding a model got wrong is recorded faithfully, wrong.

`run --inputs-from <run>` starts a run with the inputs an earlier run was started with, so an audit can be repeated
on the same inputs; `run.started` records `inputs_from`.

## Reading a register

`traceworks findings` lists the registers, each with its key and its project; `findings <key> list`, `show`,
`items`, `verify` and `render` read one (docs/cli.md, "findings"). `registers/<key>/<project>/report.html` is a
self-contained page with the findings by status, the history of the log and the verification line, written after
every applied or rejected report and after every decision; its title and a row of its own name the project.

**Which register a command means.** `findings <key> <command>` takes `--project KEY`. Without the option, in
this order:

1. The working directory lies in a declared project ("The projects of a home"): the command means the register of
   that project. When the key has no register of it, the command is refused (exit 2) and names the projects the
   key has. It does not fall back to another project: a decision would reach a register nobody named.
2. The working directory lies in no declared project: the command means the only project the key has. A key
   with more than one project refuses the command and names them (exit 2).

```
$ traceworks findings
hello  blog  example  OPEN 1
hello  shop  example  OPEN 1
$ traceworks findings hello list
traceworks: The register 'hello' has 2 projects (blog, shop): name one with --project KEY
$ traceworks findings hello list --project shop
af5377e8  OPEN       LOW       long-file  a.txt  a.txt has 12 lines, more than 10
```

And in a home that declares `blog` and `shop`, where the key has a register of `shop` alone, in the folder of
`blog`:

```
$ traceworks findings hello list
traceworks: The working directory lies in the project 'blog' ("projects" in traceworks.json), and the key 'hello'
  has no register of that project: its projects are shop; name one with --project KEY
```

The option wins, and is not compared with the working directory. The projects of a key are its folders, with or
without a log, and the projects the ledger holds anchors of for the key: a project whose log or whole folder was
removed still counts, so "the only project the key has" never becomes another one without a word, and the
refusal above can name a project that has no folder ("A register whose log is gone"). Every hint a command prints
names `--project`, so that it works whatever number of projects the key has. A command that writes says which
register it acted on, by key and project ("Deciding"). Where the ledger is there and cannot be listed, the
projects of a key cannot be said, and a command without `--project` is refused ("A ledger that cannot be
listed").

### The detail of a finding

A register holds what a report stated: the rule, the subject, the severity, the evidence. What is wrong and how to
fix it are often a model's words, and those stay out of the register and out of the next run's prompt. They are in
the record of the run: an audit's report step writes its findings as data into `findings.jsonl` in the scratch
directory, next to its report, and the record keeps every scratch file as a blob (`step.files`, docs/events.md).
One line is one JSON object, one per finding of the envelope:

```json
{"ruleKey": "path_check", "subject": "core/Mounts.java#Mounts.check(Path)", "severity": "HIGH",
 "item": "core/Mounts.java#Mounts.check(Path)", "number": "A012", "source": "model", "details": [
  {"file": "core/Mounts.java", "line": 41, "quote": "if (path.startsWith(home)) {", "severity": "high",
   "model": {"issue": "The check is lexical: a symbolic link gets past it.", "fix": "Compare the real path."}}]}
```

| Field | Meaning |
|---|---|
| `ruleKey`, `subject` | exactly as in the envelope, so the two join |
| `severity` | as in the envelope |
| `item` | the item of the audit the finding came from (an area, a candidate file, a section, a class), by the name it has in the envelope's `items`; absent for a measured finding |
| `number` | the number or id the item has in the report of this run (`A012`, `C03`, `cli.md#run`), which may be another one in the next run; absent for a measured finding |
| `source` | `model` for a judgement of an AI, `measured` for a finding of a deterministic step |
| `details[]` | the places: `file`, `line` and `quote` (the line as the step found it in the file), then whatever else the step measured, by its name |
| `details[].model` | the words of the model, by their names (`issue` and `fix`, `mutation` and `why_uncaught`, `reason` and `target`): nothing checked them |

`traceworks findings <key> show <id>` prints, below the finding, the detail from the run that last reported it
(`last_seen`): it finds the run in the home, reads the recorded `findings.jsonl` of the last step that left one, and
prints the entries with the finding's rule and subject. What is under `model` is headed `the model's words, not
checked`; every value is printed on one line, with control characters made harmless, and cut. When the run is no
longer in the home, left no `findings.jsonl` or has no entry for the finding, one line says so and the rest is shown.
The file is a convention of the audit, not something the register reads: `{{ audit_findings }}`, `state.json`, the
log and its blobs never hold it. For a script, `findings <key> list --json` prints the listed findings as a JSON
array, each with the fields it has in `state.json`; the detail is joined from the run's `findings.jsonl` by
`rule_key` and `subject`.

## Deciding

A person accepts a finding as a risk, dismisses it or reopens it:

```bash
traceworks findings architecture accept 9d3692a8 --note "a fold over every event type stays one" --until 2027-03-31
traceworks findings architecture dismiss 9d3692a8 --note "not ours"
traceworks findings architecture reopen 9d3692a8 --note "fix it after all"
```

Each of them takes `--project KEY` where the key has more than one project ("Reading a register"); a decision is
a line of that one register and covers no other project. The line a command prints names the register it acted
on, since the project may have come from the working directory:

```
accepted 9d3692a8 as a risk in the register architecture of shop, review by 2027-03-31
dismissed 9d3692a8 in the register architecture of shop
reopened 9d3692a8 in the register architecture of shop
```

`accept` and `dismiss` take an `OPEN` or
`AWAITING_VERIFICATION` finding and need a note; `accept` freezes the
finding's evidence for the comparison with later reports and sets a review date (`--until`, at most 180 days ahead,
which is the default). `reopen` takes an accepted, dismissed or resolved finding. A wrong transition is refused and
records nothing. Every decision is a line of the log with the actor (docs/cli.md, "Actor"), the agent when one
decided ("Who decided", below), the time, the note and the state after it; `signature` is `null` until signed
decisions exist.

**A decision that is recorded and not finished.** The line of the decision comes first; `state.json`,
`report.html` and the anchor in the ledger follow it. When one of those cannot be written, the decision stays
recorded, and the command says so and exits 6, not 0: the confirmation is on standard output, and a warning on
standard error says what is missing and that `findings <key> render --project <project>` writes it once it can be
written.

```
dismissed eb43551d in the register hello of shop
traceworks: warning: the decision is recorded, but it is not anchored in the ledger (Cannot write the ledger ...);
  'traceworks findings hello render --project shop' anchors it once the ledger can be written
```

```
traceworks: warning: the decision is recorded in the log of the register, but state.json or report.html of the
  register could not be written (...); 'traceworks findings hello render --project shop' writes them once they
  can be written
```

So a script must not read this 6 as "nothing happened": the line `dismissed ...` on standard output says that the
decision is in the log. A decision that could not be recorded at all prints no such line (`The register 'hello' of
the project 'shop' cannot be written, and the decision is not recorded: ...`, exit 6).

### Who decided

The line of a decision names its **actor**: who ran the command (`TRACEWORKS_ACTOR`, else `actor` in
`traceworks.json`, else `user@host`; docs/cli.md, "Actor"). An AI agent that works with the operator's user is that
user to the machine, so a decision it makes on the operator's word would read as the operator's own. An agent
therefore **names itself**, and the register records it beside the actor:

```bash
traceworks findings security dismiss 9d3692a8 --note "RunPaths refuses this mount before the call" --agent claude-code
TRACEWORKS_AGENT=claude-code traceworks findings security accept 4a4dd633 --note "L12"   # set once by a harness
```

- **`--agent NAME`** on `accept`, `dismiss` and `reopen`, or the environment variable **`TRACEWORKS_AGENT`** for a
  harness that sets it once for everything its agent runs. The option wins over the variable. Only these three
  commands read either: `list`, `show` and `verify` decide nothing.
- **The name** is a label of at most 64 characters: ASCII letters and digits, the characters `. _ - : / @ +`, and
  single spaces between them (`claude-code`, `Claude Code 2.1`, `pi:astra@build-7`). White space around it is
  dropped. So no name holds a control or format character, a line break, two spaces in a row, a quote, a bracket,
  a comma or a `|`: it stands in a line and a column of every view as it is.
- **A blank name is refused, not ignored**, and so is one beyond these bounds (a usage error, exit 2, nothing is
  recorded): whoever set the option or the variable meant to name an agent, and the decision must not be recorded
  as a person's instead. To decide as a person in a shell where the variable is set, unset it for the command.
- **The line** of the decision takes one more field, `agent`, beside `actor`, which means what it always did:

  ```json
  {"seq":41,"at":"2026-11-17T10:02:11.418Z","type":"finding.dismissed","register":"security","prev":"...",
   "actor":"ana@host","agent":"claude-code","evidence":"...","id":"9d3692a8...","note":"...","severity":"LOW",
   "signature":null,"state_blob":"..."}
  ```

- **A decision without an agent** has no `agent` key, in its line and everywhere else, and reads exactly as
  before: every view below says something only for a decision an agent made.

| Where | What it says |
|---|---|
| `findings <key> accept`, `dismiss`, `reopen` | the name as it was recorded: `dismissed 9d3692a8 in the register security of shop through the agent claude-code` |
| `findings <key> show <id>` | `Decision  DISMISSED by ana@host through the agent claude-code at 2026-11-17T10:02:11.418Z, ...` |
| `findings <key> list` | the row ends with `(through the agent claude-code)`; inside the note of a row that has one: `(decided on LOW through the agent claude-code)`, `(dismissed on LOW through the agent claude-code before, not in force)` |
| `state.json`, `findings <key> list --json` | `disposition.agent` |
| `report.html` | `dismissed by ana@host through the agent claude-code at ...`, and in the history `actor ana@host · agent claude-code` |
| the register folder of a run (`findings.jsonl`, `resolved.jsonl`) | `disposition.agent`, next to `disposition.actor` |

`{{ audit_findings }}` carries the note of a decision and neither its actor nor its agent, and the diff a hook gets
("Hooks") carries no decision at all; both are as they were. A manual reopening removes the decision from its
finding, so the agent of a reopening is in its line of the log and in the history of `report.html`, not in the
state.

**The name is what the caller says, and nothing checks it.** It is self-declared and unsigned, like the actor:

- An agent that leaves the name out is recorded as the actor alone, and its decision reads as a person's. Nothing
  in Traceworks can tell an agent from the person whose user it works with; a harness that sets `TRACEWORKS_AGENT`
  for its agent is what makes the name reliable, as far as that harness is.
- Any caller can give any name: a person can decide as `claude-code`, and one agent as another.
- So `agent` says "the caller named this agent", and its absence says "the caller named none". Neither is proof of
  who decided. Signatures of decisions would be; they are not built ("What a register does not do").

**A register with such a decision needs a version that knows the field.** A version from before 2026-11-17 reads
only `actor` from the line of a decision: it would fold the decision as the actor's own, show it so in every view,
and find the state the line recorded (which names the agent) different from its fold, so its `verify` would fail
and its next write would leave lines that this version folds to other states. Therefore this version writes, once
per register and before its first line that names an agent (a reopening included), the line `register.requires`
with `"feature": "decision_agent"`. A version that knows `register.requires` and not this feature refuses to replay
the log (`seq N: the register requires 'decision_agent', which this version of Traceworks does not know: a newer
version wrote it`), as an older one refuses the line itself ("What a decision covers", below); neither shows the
agent's decision as the operator's, and neither writes. This is read from the code of that version; no binary of
it was run against such a register. A register in which no agent decided gets no such line, has the bytes an
earlier version would have written, and stays readable and writable by the version before this one. A register of
an earlier version verifies and replays unchanged.

### What a decision covers

A decision is made on what the finding was at that moment, and the register keeps that: the line of the decision
names the `severity` and the `evidence` the finding had, and the finding keeps the severity with its decision. A
later report overwrites the finding's severity and evidence, never those of the decision. So:

- A report that states the finding with a **higher severity** opens it again (above, "Reconciliation"). This is
  the default, `"decision_on_higher_severity": "reopen"` in `traceworks.json`.
- With `"decision_on_higher_severity": "keep"` the decision stays whatever the severity.
  The setting is the operator's and not the package's: a decision is the operator's, and a package must not be able
  to widen what one covers. `report.applied` names the value each report was applied with.
- Wherever a decided finding is shown and its severity is no longer the one decided on, the register says both:
  `findings <key> list` ends the row with `(decided on LOW)`, `show` and `report.html` print `made on severity
  LOW, not on the CRITICAL the finding has now`, `{{ audit_findings }}` adds `| Decided on: LOW, not on CRITICAL`,
  and `state.json` and `list --json` hold `disposition.severity`. Both hold that key only where the two severities
  differ. In `list --json`, which is made from the fold of the log, a decision without the key was made on the
  severity shown. In `state.json` that holds from the line `register.requires` on (below): the states an earlier
  version recorded never have the key, also where a report had raised the finding above its decision.
- A decision that is no longer in force stays with its finding as what was once decided: after a report or a
  person opened the finding again, after its review date passed, or after it was resolved. `list` ends such a row
  with `(dismissed on LOW before, not in force)`, `show` and `report.html` say `not in force: the finding is
  OPEN`, and `list --json` holds `"in_force": false` in the `disposition` (the key is absent while the decision is
  in force), so a script need not compare the status with the kind.
- `show` also prints the evidence the decision was made on when the finding has other evidence now (`Made on
  evidence: ...`).
- Other evidence alone opens nothing. A dismissed finding of a coarse subject that comes back with the same
  severity and other evidence stays dismissed; `show` is where the difference can be seen.

**A register written before this rule** verifies and replays unchanged. Its `report.applied` lines name no
`decision_on_higher_severity`, and they fold as that version applied them: the decision stayed. The fold of the log
still knows the severity each of its decisions was made on (the severity the finding had at the line of the
decision), so the first report this version applies reopens a finding that an earlier report had raised above its
decision, and from that line on the states name the severity decided on. The reason names the severity decided on
and the one stated now, not the one the register showed in between: a finding shown as `CRITICAL`, dismissed as
`LOW` and now stated as `MEDIUM` is reopened with `higher_severity: LOW → MEDIUM`. The evidence of a dismissal
recorded before this rule is not in its line; `show` prints none for it.

**A register this version has written must not be written by an earlier one.** An earlier version folds every
decision as kept and takes the first of two findings of one identity, so its fold of the lines of this version
differs from the states they recorded; were it to apply its next report on top, the log would hold lines that no
version folds to their states, and `verify` would fail for good. This version therefore writes one line before its
first line that needs the rule, `register.requires` with `"feature": "decision_severity"`: on a new register after
`register.created`, on an older register before its first report or its first acceptance or dismissal (a manual
reopening needs no such line and writes none). A version from before 2026-11-14 does not know the type and refuses
to replay the log, so it writes nothing: a run ends with `register <key>: not applied — the register <key> could
not be written: seq N: unknown register event register.requires`, its `findings <key>` commands end with
`traceworks: internal error: IllegalStateException: seq N: unknown register event register.requires`, and its
`findings <key> verify` names the line as a problem. This is read from the code of that version; no binary of it
was run against such a register. It is a refusal and not a repair; go on with a version that knows the line. The same line guards the
future: a version that meets a `feature` it does not know refuses too.

## Hooks

With `hooks.register_changed` in `traceworks.json`, a program of the operator runs on the host after every audit
run whose report changed the register (something new, resolved, reopened or due for review) or was rejected:

```json
{"hooks": {"register_changed": ["/path/to/examples/hooks/github-issues.sh", "owner/repo"], "timeout_seconds": 60}}
```

It is started without a shell, with the operator's own environment, in the register's folder, after the register's
lock is released, and gets the diff as JSON on its standard input:

```
{"format": 1, "register": "architecture", "project": "shop", "workflow_id": "...", "run_id": "...",
 "report_valid": true,
 "new": [...], "resolved": [...], "reopened": [...], "review_due": [...], "still_open": [...],
 "counts": {"new": 1, "resolved": 3, ...}}
```

`register` and `project` together name the register the diff is of: one audit workflow feeds a register per
project, and a hook that files a finding somewhere must say which project it is about. The folder the hook runs in
is that register's, `registers/<key>/<project>/`.

Each finding carries `id`, `rule_key`, `subject`, `severity`, `title`, `description`, `evidence`, `tags`,
`status`, `unverified_evidence`, `occurrences` and `regressions`; a resolved or reopened one its `reason`
(`regression`, `seen_again`, `worse: ...` for an accepted risk whose evidence got worse, and `higher_severity: LOW →
CRITICAL` for an accepted or dismissed finding stated above the severity its decision was made on). The
hook's exit code, duration and the tail of its standard error are recorded as `hook.ran`; a timeout is exit 124, a
program that does not start 127; the summary says `hook ran` or `hook exited N`. A failing hook never changes the
run's exit code. `examples/hooks/github-issues.sh` opens a GitHub issue per new or reopened finding with the GitHub
CLI, and names the project in the issue and in the command it points to (`findings <key> show <id> --project
<project>`); the same shape serves another tracker. A workflow never configures a hook: the diff of a run is the operator's
to act on.

## The register folder

The registers lie in `<home>/registers/`, or where `registers_dir` in `traceworks.json` puts them (docs/cli.md,
"Places of the runs, the ledger and the registers"): one folder per key, and in it one folder per project. A
register names a run by its id, so the registers folder can be moved as a whole.

```
<home>/registers/<key>/<project>/
  events.jsonl     the log, hash-chained like a run's (docs/events.md, "The register log"); its first line,
                   register.created, names the project
  blobs/<sha256>   the report texts, the findings and the items each report stated, and the state after every change
  state.json       the state folded from the log, as sorted compact JSON: the same state gives the same bytes; the
                   key items, and item of a finding, only for a register whose audit lists items, and the key
                   project only for a register whose log names one (a register from before keeps its bytes)
  report.html      the page
  register.lock    taken by every writer (an audit run, a decision), so writers of every process take turns
```

The lock is per key and project: the writers of two projects of one key do not wait for each other. None of this is
a git repository. What ties a register to something outside its folder is its anchors in the ledger ("The anchor").

A reader takes no lock and reads the log, never `state.json`; `findings <key> verify` checks the chain of the log
(seq, prev, the key of every line), that the log names the project of the folder it lies in, that every blob a line
points to exists and hashes to its name, that the state
each applied report and each decision recorded equals the fold of the log up to there, and that `state.json` equals
the final fold. One changed byte of a line breaks the chain from the next line on; a blob edited in place no longer
matches its name; a state edited by hand differs from the fold. A writer that was cut off mid-line leaves a torn
tail, which the next writer discards and records as `register.repaired`. A command that is refused writes nothing,
also no such note.

`traceworks verify` without a run checks every register of the home the same way, and lists a register the ledger
anchors that the home no longer holds (docs/cli.md, "verify without a run").

**Only the two `verify` commands check the chain.** A line changed in the middle of a log is found by `findings
<key> verify` and by `traceworks verify` (exit 8). `findings <key> list`, a decision and the feed of a run replay
the log and do not compare `prev`: they still work on such a log, as long as its lines fold. Run `verify` before
you rely on a register, and after anything touched its folder.

### A folder that was copied

The project of a register is in its log, not only in the name of its folder: `register.created` names it, and a
log must begin with that line (before it only a note of the tool may stand, `register.repaired` or
`register.unanchored`). So a folder that was copied or renamed to another project is not that project's register,
and a log that lost its first line is the register of no project. Every path refuses it: the start check of a run,
the feed at its end, every `findings` command and both `verify` commands.

```
$ cp -r registers/hello/shop registers/hello/copy
$ traceworks findings
hello  blog  example  OPEN 1
hello  copy  refused: The register 'hello' of the project 'copy' cannot be used: the log of the register hello of
  copy names the project shop: a folder that was copied or renamed is not the register of copy; 'traceworks
  findings hello verify --project copy' says what is wrong
hello  shop  example  DISMISSED 1
$ traceworks verify
...
problems (1)
  anchors.5.hello.copy  the log of the register hello of copy names the project shop: a folder that was copied or
    renamed is not the register of copy (the register's log ends with the line 19de9726...; its lines have the
    SHA-256 c8700c62...)
```

**A log says once whose it is.** `register.created` is the line a log begins with, and there is one. A further
`register.created` is not taken, whatever it names: appended to a copied folder, it would hand that folder, with
its decisions, to another project or another workflow. `register.assigned` is the one other line that names a
project, once, in a log whose `register.created` names none ("A register from before projects"); a log that
begins with `register.assigned` is refused like any log that does not begin with `register.created`. The replay
refuses such a log on every path, and both `verify` commands name the line:

```
seq 5: register.created stands after the line the log begins with (seq 0, register.created): a log says once
  whose it is, in its first line, and a later line that says it again is not taken
```

Where the log does not begin with `register.created` either, the sentence ends `(seq 1, register.requires): the
log does not begin with the line that says whose it is, and a later line that says it is not taken`.

A register that cannot be used is a refusal with the reason and the command that says more (exit 6, and 8 from
`verify`), never an internal error: a copied folder, a log that cannot be read or replayed, a blob that is gone, a
folder that cannot be listed or read (`The registers cannot be listed: ...`). The overview prints one line for such
a register, goes on to the others, and exits 6 at its end when any register in it was refused. Without
`--project`, a command on the key `hello` is now refused because the key has three projects.

**A part that cannot be read says why.** The message is `Cannot read <file>: <cause>`, and for a blob `Cannot read
blob <sha256>: <cause> (<path>)`. The cause is `a folder is in its place`, `access denied`, `no such file` or `a
loop of links`; anything else is the operating system's own reason, in the language of the machine. A log, a
`state.json` or a blob in a folder that cannot be entered cannot be read: it is not taken for an empty log, a
missing state file or a deleted blob, and neither is a folder in the place of `state.json`. So such a register is
refused as one that cannot be read, not as one that is gone:

```
hello  shop  refused: The register 'hello' of the project 'shop' cannot be used: Cannot read
  <home>/registers/hello/shop/events.jsonl: access denied; 'traceworks findings hello verify --project shop' says
  what is wrong
```

Both `verify` commands say `the register cannot be read: Cannot read <home>/registers/hello/shop/events.jsonl:
access denied` (exit 8), and an acknowledgement covers that cause only: a log that was acknowledged with a folder
in its place and is then denied is listed again. Two things this does not tell apart: two states that cannot be
read for the same cause are one sentence, so the second stays acknowledged; and where the cause is the operating
system's own words, the sentence, and an acknowledgement of it, depend on the language of the machine.

**A blob that was changed** is not a refusal but damage: a command that reads a blob whose content is not what
its name says ends with exit 8, and its message ends with the command that checks this register (without
`--project` for a register from before). The overview lists such a register as `damaged` and goes on:

```
hello  shop  damaged: The blob 93cf1f82... holds other content than its name says (its content has the SHA-256
  e6b0833a...), so it is not what the line that names it pointed to; check the register with 'traceworks findings
  hello verify --project shop'
```

So the overview has three exit codes: 8 when any register in it had a changed blob, else 6 when any was refused,
else 0. It reads only some of the blobs; `findings <key> verify` and `traceworks verify` check every blob a line
names.

### A register from before projects

A version from before registers had projects kept one register per key, in the folder of the key itself
(`registers/<key>/events.jsonl`), and anchored it under the key alone. Such a register is the register of no
project, and **nothing assigns it by itself**, not even to the project of the next run: Traceworks cannot know which
project those findings and decisions were about.

```
$ traceworks findings
hello  not assigned to a project: a register from before registers had projects; assign it with 'traceworks
  findings hello assign --project KEY'
$ traceworks findings hello assign --project shop
assigned the register hello to the project shop: <home>/registers/hello/shop
```

- **Until it is assigned**, every run with that key is refused before it exists, and so is every `findings <key>`
  command but `assign` and `verify` (exit 6). The message names the command.
- **`findings <key> assign --project KEY`** judges the register first: its chain and its blobs must verify. One
  that does not is refused with what is wrong (exit 8), and nothing is written; `findings <key> verify` without
  `--project` works on a register from before and says more. Then, under the register's lock, `assign` appends the
  line `register.assigned` with the project and the actor, writes `state.json` and `report.html` again, moves the
  register to `registers/<key>/<project>/` and anchors its head under the new path ("The anchor").
- **A register that does not match its anchors is assigned all the same**, and `assign` says so in a paragraph of
  its own, after an empty line, on standard output:

  ```
  assigned the register hello to the project shop: <home>/registers/hello/shop

  note: the register does not match its anchors in the ledger, and it is assigned as it is: 'traceworks findings
    hello verify --project shop' says where, and goes on reporting it
  ```

  It is a note and not a refusal: the mismatch stays in the record, `verify` fails for the register before and
  after (exit 8), and a refusal would leave such a register without any way to a project. `assign` compares the
  register with the anchors of the project it is assigned to as well as with the earlier ones under the key.
- **The log is the same chain before and after.** No line is rewritten; from `register.assigned` on, the states
  name the project. A version that does not know the line refuses to replay such a log, which is right: it would
  keep the register under its key alone.
- **Refused** (exit 6, nothing is written): a register that is not from before, a project that has a register of
  the key already or a folder in its place, an assignment that was begun for another project, and a register that
  cannot be written (`The register 'hello' cannot be written, and it is not assigned: ...`). `assign` without
  `--project` is a usage error (exit 2). A project may have any valid key, also the name of a part of a register's
  folder such as `blobs`. A refused `assign` leaves the folder as it was: no line, and no `register.lock` where
  there was none. That holds for the registers of the projects too: `assign` on a key whose registers are all of
  projects is refused without writing, also no `register.repaired` where a log ends in a fragment.
- **A register from before is assigned once.** A key had one register from before, so once a project's log holds
  `register.assigned`, or the ledger anchors that line for a project, whatever lies at the key again is a copy of
  what was assigned (a backup put back, a folder copied in). `assign` refuses it, for any project, also the one it
  was assigned to while that project has its register (exit 6, nothing is written):

  ```
  traceworks: The register 'hello' cannot be assigned to portal: the register from before registers had projects
    of this key was assigned to the project shop already (the log of the register hello of shop holds
    register.assigned). A register from before is assigned once, so what lies at the key is a copy of what was
    assigned: remove it
  ```

  Where the folder of that project is gone and only the ledger knows the assignment, the bracket reads `(the
  ledger anchors that assignment)`. What cannot be asked is not taken as "not assigned": with a project's log that
  cannot be read (`the log of the register hello of shop cannot be read (Cannot read <path>: access denied)`), or
  a ledger that cannot say, the refusal ends `..., so it cannot be said whether the register from before registers
  had projects was assigned to a project already`. This check comes before the register is
  verified, so a copy that is also damaged is refused with exit 6, not 8.
- **The one exception: the same project, whose folder is lost.** When the ledger anchors the assignment to a
  project and that project has no folder (the register was lost, and a backup of the register from before was put
  back at the key), `assign --project` of that same project is taken (exit 0). The log of the backup does not hold
  what the ledger anchored after it, so the note of a register that does not match its anchors follows, the new
  anchor says `{"register.assigned":1}` and the actor, and `verify` goes on reporting the anchors of the lost log
  (exit 8). The decisions made after the backup are gone. A folder at the project's place, also an empty one, is
  no lost folder: `the project shop has a register of hello already, or a folder in its place` (exit 6).
- **Two logs that both say it** are found by both `verify` commands, at each of the two projects (exit 8): `the log
  of the register hello of shop holds register.assigned, and so does that of the project portal: a register from
  before registers had projects is assigned once, so one of the two is a copy of the other. Remove the folder of
  the copy ('traceworks findings hello verify --project portal' checks the other one)`.
- **What the copy at the key still does.** While it lies there, every other command on the key, and the overview,
  is refused as for any register from before, with the hint to assign it; only `assign` says that it is a copy and
  has to be removed. `traceworks verify` without a run does not list the copy as a problem.
- **An assignment that was cut off** is finished by the same command and changes nothing twice: the line is there
  once, a move goes on from where it stopped, and a register that lacks only its anchor gets it. After the move
  and before its anchor the register is usable; its next write records the line of the assignment as found without
  an anchor (`register.unanchored`). An assignment whose line is written and that then fails says exactly that
  (exit 6): `The assignment of the register 'hello' to shop is recorded in its log (register.assigned) and not
  finished: ...; 'traceworks findings hello assign --project shop' finishes it`. Until it is finished, the register
  is said as what it is:

  ```
  $ traceworks findings
  hello  not assigned to a project: its assignment to shop was begun and not finished; finish it with 'traceworks
    findings hello assign --project shop'
  $ traceworks findings hello assign --project portal
  traceworks: The assignment of the register 'hello' was begun for the project shop and not finished: finish it
    with 'traceworks findings hello assign --project shop'
  ```

  `findings <key> verify` (exit 8) and `traceworks verify` (under `registers.<key>`) report it too: `the
  assignment of the register hello to the project shop was begun (register.assigned at seq 6) and not finished:
  'traceworks findings hello assign --project shop' finishes it`.
- **The staging folder.** The move goes through `registers/<key>/.assigning`, and nothing is staged before the
  assignment is certain: a refused `assign` leaves every file of the register with its bytes at its place, with or
  without a staging folder beside it. Such a folder without a begun log is nothing, and every reader ignores it.
  A `register.lock` and a zero-byte `events.jsonl` in it, beside the ones at the key, are leftovers of an attempt
  that was cut off; the assignment that goes through removes them. Any other part that lies at both places (an
  `events.jsonl` with a byte, `blobs`, `state.json`, `report.html`) refuses the assignment (exit 6), before
  anything else is asked of the register, and nothing is moved or written:

  ```
  traceworks: The register 'hello' cannot be assigned: a part of the register hello lies in two places:
    <home>/registers/hello/blobs and <home>/registers/hello/.assigning/blobs; one of the two is a copy, and an
    assignment replaces neither: remove the copy by hand
  ```

  No move of an assignment replaces a file or a folder. `assign` holds the `register.lock` of the key while the
  key has one (a lock in the staging folder counts only when the key has none), and waits while another process
  holds it. Beside it an assignment takes a lock of its own, the file `.assign.lock` in the folder of the key, and
  removes it when it is done; a file of that name with content is none an assignment leaves, and `assign` refuses
  it (`the lock of the assignment <file> holds content, which no assignment leaves at its place: remove the file`).
  One thing stays as it was: when a copy lies in `.assigning` beside a project the register was assigned
  to, the refusal and the overview still speak of what lies at the key and advise `assign --project KEY`.
- **A register from before that is gone.** When the ledger holds the anchors an earlier version wrote under the
  key, and the home holds neither that register nor one that was assigned from it, no register of the key is begun
  for any project ("A register whose log is gone").

An earlier version of Traceworks does not see a register of this layout: it looks for a log in the folder of the
key and finds none, so its `findings` lists the key with no findings, and it refuses a ledger that holds anchors of
this version ("The anchor"). Go on with this version in a home it has written.

### The anchor

The chain of a log says that no line before the last was changed. By itself it cannot say that the last line is the
one that was written (a `hook.ran` or a `finding.new` names no state), or that no line was cut from the end and
`state.json` written again. So every write of a register anchors its head outside the register's folder, in the
ledger (docs/events.md, "The ledger"): after a report was applied or rejected, after the hook's line, after a
decision, after an assignment and after `findings <key> render`, the ledger gets one commit that adds a line with
the sequence number and the SHA-256 of the last line of the log, and with what was written since the anchor before

```
{"seq":17,"head":"<the SHA-256 of line 17>","lines":{"finding.new":1,"report.applied":1},"run":"<run id>"}
```

to `anchors/<key>/<project>.jsonl` of the ledger (`<home>/ledger/`, or where `ledger_dir` puts it; the register
itself may lie where `registers_dir` puts it), under the message `anchor <key>/<project> seq 17 sha256:<the whole
file>`. The ledger is one for the home, so it holds one such file per register: per key and project. It keeps every
anchor, not only the last, and a write that added no line adds no anchor. `findings <key> verify` reads them and
checks each against the log: the line with that `seq` must exist and hash to that `head`.

**An anchor says what was written.** The ledger is where one looks to see what happened in a home. So an anchor
covers the lines the register's log gained since the last anchor the log still agrees with, and says of them what
the table below lists. The log agrees with an anchor when it holds a line of that `seq` and the line hashes to
that `head`; the anchors are taken in the order of the ledger, and where the log agrees with none, the anchor
covers all lines. For a log nobody touched that is the anchor before. For a log that was cut and written on, it is
the last anchor before the cut, so the new anchor says what really lies after it. `verify` uses the same rule. A
write that leaves the head on a line the ledger anchors already adds no anchor, so no anchor covers no line and
none says `"lines":{}`: after a cut, a `render` anchors nothing again, and `verify` reports the anchors of the
lines that are gone either way.

| Field | What it holds |
|---|---|
| `seq`, `head` | the sequence number and the SHA-256 of the last line of the log |
| `lines` | each type of the covered lines with its count, sorted by type: `{"finding.new":3,"report.applied":1}` |
| `run` | the run the covered lines name (`run_id`): the run whose report was applied or rejected, or whose hook ran. Absent when none names one; a sorted list when they name more than one |
| `actor` | who decided or assigned, as the covered lines record it. Absent when no line has one; a sorted list for more than one |
| `agent` | the agent of a decision, the same way ("Who decided") |
| `decided` | the ids of the findings a person accepted, dismissed or reopened in the covered lines, sorted. Absent when there is none. A finding a report reopened is not among them |

The two anchors of a first run and of a decision an agent made after it:

```
{"seq":3,"head":"ff358cb4...","lines":{"finding.new":1,"register.created":1,"register.requires":1,"report.applied":1},"run":"acc049dc-4852-4a72-b79f-378df0e2f18a"}
{"seq":5,"head":"ae8a6cbd...","lines":{"finding.dismissed":1,"register.requires":1},"actor":"anna@laptop","agent":"claude-code","decided":["af5377e8c74090f6231b9b040c3c1579fcf84e6b738d0c51fa5016353ec23cd2"]}
```

- **Nothing else is in it:** no title, subject, note, severity or evidence. The ledger still holds no content of a
  register, so pushing the ledger somewhere shows that a finding was dismissed, by whom and through which agent,
  and not what the finding says.
- **A value has the shape of its field.** `run` is a run id (at most 255 characters), an entry of `decided` is the
  id of a finding (64 lower-case hex digits), `agent` is a name within the bounds of an agent's name ("Who
  decided": at most 64 characters, ASCII letters and digits, `. _ - : / @ +` and single spaces), and a type in
  `lines` is one this version writes. `actor` is held as it is: 1 to 255 characters, none of them a control
  character, so a name such as `"actor":"Zoë Brontë <zoe@example.org>"` stands in the anchor as the register's
  log has it. A covered line whose value has another shape gives the marker `(malformed)` in its place, for the
  writer of the anchor and for `verify` alike; the register's log keeps the value as it is. A line of an anchor
  file that holds any other value is no anchor: `verify` says `line 3 of anchors/hello/shop.jsonl in the ledger
  ... is no anchor` (`anchor    NOT checked`, exit 8).
- **The actor is the one free text an anchor holds.** Every other value is an id, a count or a bounded name. A
  line that someone appended to a register by hand can therefore put any text of the actor's shape into the
  ledger, up to 255 characters; format characters such as bidirectional marks pass, since only control characters
  are refused.
- **No writer says what it wrote.** The line is derived in one place, from the lines of the log, when the anchor
  is written. An anchor that follows one the ledger could not take covers the lines of both writes, and says so by
  its counts.
- **`verify` derives it again.** `findings <key> verify` and `traceworks verify` without a run compute the same
  from the log for every anchor of this shape and report each difference, exit 8: which anchor, which field, what
  the anchor says and what the lines it covers give.

  ```
  chain     ok (6 events, head ae8a6cbd...)
  anchor    NOT ok
  | seq 5: the anchor in the ledger says lines finding.dismissed 2,
  |+ register.requires 1, and the lines it covers (seq 4 to 5) give
  |+ finding.dismissed 1, register.requires 1
  | seq 5: the anchor in the ledger says actor bert@laptop, and the lines it
  |+ covers (seq 4 to 5) give actor anna@laptop
  register hello of shop does not verify
  ```

- **The earlier shape says less.** An anchor in `registers/<key>.jsonl`, which an earlier version wrote, holds
  `seq` and `head` alone and is checked by those. A line with `seq` and `head` alone in a file per key and project
  is nothing this version writes, and `verify` reports it (`the anchor in the ledger does not say what was written
  (it has no lines)`).
- **It is derived, and unsigned.** What the anchor says is true of the log as it stood when the anchor was
  written, by whoever could write the ledger then ("What an anchor does not prove", below).

| What was done to the register's folder | What `verify` says |
|---|---|
| the last line was changed | `seq 17: the line is not the one the ledger anchors`, exit 8 |
| lines were cut from the end, also when `render` wrote `state.json` again | `seq 17: the ledger anchors this line, but the log ends at seq 15`, exit 8 |
| lines were cut and a later write put other lines there | the old anchor of that `seq` no longer matches, exit 8, from then on |
| nothing | `anchor    ok (seq 17 is the head the ledger holds, 9 anchors)` |

The anchor is written under the register's lock, after the register's own files, so the anchors of two writers are
in the order of their lines; the ledger's lock serialises them with the runs that end at the same time.

**The anchors of an assigned register.** An earlier version anchored a register under its key alone, in
`registers/<key>.jsonl` of the ledger (`register <key> seq <n> sha256:...`). This version reads that file and
never adds to it. For a register that was assigned to its project ("A register from before projects") the anchors
are the lines of that file followed by the lines of `anchors/<key>/<project>.jsonl`, so what the ledger said of
the log before the assignment still holds it: a line changed before the assignment is found afterwards too. The
two shapes lie in two folders of the ledger, so no key is in the way of an earlier file.

These cases are said plainly and do not fail `verify`:

- **A register without an anchor.** A register written before anchors existed has none: `anchor    none: the ledger
  holds no anchor of this register ...`. The next write anchors it, and from then on its end is watched. Its earlier
  lines are covered by the chain up to that anchor. A ledger that was left behind when `ledger_dir` changed reads the
  same: the configured ledger holds no anchor, and the line names "another ledger" as a reason. Move the ledger to
  its new place and `verify` compares again; Traceworks moves nothing by itself (docs/cli.md, "Places of the runs,
  the ledger and the registers"). `traceworks verify` of a run fails in that case, because a run always has a ledger
  entry; a register may honestly have no anchor, so its `verify` cannot tell the two apart and does not fail.
- **A ledger that could not be written** (no git, a full disk, a folder that cannot be created). The register's
  write is kept: a report that was applied stays applied, a decision stays recorded. The run's summary ends with
  `not anchored in the ledger (<why>)`, and its exit code stays the run's. A decision or a `render` prints a
  warning on standard error and exits 6 ("Deciding"), and `verify` says
  `anchor    ok up to seq 15; seq 16 to 17 are not anchored, so they are unproven ...`.
- **Lines after the last anchor.** They are what a ledger that could not be written leaves, and what a writer leaves
  that was cut off between the register and the ledger. They are also what someone leaves who can write the
  register's folder and appends a line that chains, a forged decision with its state blob for one. Nothing tells
  these apart, so such lines are **accepted**: `verify` holds (exit 0) and calls them unproven, by their sequence
  numbers, and the next write, a run, a decision or `findings <key> render`, takes the log as it is and anchors its
  head. It does not do so silently. Before it writes, under the lock, it compares the log with the anchors, and
  when lines lie after the last anchor it records the line `register.unanchored` with `first_seq` and `last_seq`,
  and says so: `seq 16 to 17 were not anchored before this write and are taken unproven: lines that were never
  anchored, ... (recorded as register.unanchored)`, in the run's summary line of the register or as a `traceworks:
  note:` of the command. That line stays in the log, and in the history of `report.html`. A gap is recorded once:
  while the ledger still cannot take an anchor, every later write finds the same gap, adds no line and says `seq 16
  to 19 are still not anchored: the gap is recorded at seq 18, and the anchor of every write since failed, as
  reported then`. (A line appended by hand inside a gap that is already recorded is not told from those writes.) The
  hook's line of a run whose report could not be anchored a moment before records no gap either: the summary
  already says `not anchored in the ledger`. So an appended line is never found as tampering, but
  it leaves a trace that says when it was first seen and that no anchor vouched for it. A writer that finds the log
  not matching its anchors (a cut end, a changed line) says that too and goes on; `verify` fails for such a register
  until someone decides what to do with it.

**What an anchor does not prove.** It says where the log stood when it was written, and what the lines it covers
were, by whoever could write the ledger then. What it says of the lines is derived from them and signed by nobody:
a forged decision gets a well-formed anchor that names the forged actor, as a real one does. With write access to the home and the ledger, someone can append a line that chains to the register's
log (a forged decision, with its state blob) and then append the anchor of the new head, under the message
`anchor <key>/<project> seq <n> sha256:<the whole file>`: `verify` says `anchor    ok`, and nothing shows it. What the
ledger does find is a register or an anchor file that was rewritten, since anchors are only added to (docs/events.md,
"How the ledger is judged"). Signatures of decisions, by a key the writer of the home does not hold, would close
the gap; they are not built. And the first anchor in a new ledger (docs/events.md, "A new ledger") proves nothing
about the lines before it.

A ledger that exists and **cannot be read** fails `verify` (exit 8): the comparison was not made, and the line says
why (`anchor    NOT checked`). That is a ledger whose path is a file, whose `HEAD` names nothing, whose objects
are gone, or whose branch file was emptied, zeroed or removed (git then answers as for a repository without a
commit, but the objects of the old history are still there). Only a ledger that does not exist yet, one that holds
no commit, no reference and no object at all, and one whose `HEAD` holds no file of the register read as "no
anchor". A write to a ledger that cannot be read is refused by the ledger, never started over as a new history:
the register's write is kept and says `not anchored in the ledger`.

A reader takes no lock. `verify` reads the anchors first and the log after: a write in between leaves lines after
the last anchor it read, which it names as above, and never an anchor beyond the lines it read.

What the anchor does not do: it does not protect against someone who can write the ledger too (keep a copy of the
ledger where your runs cannot write, docs/security.md), and it cannot tell a register whose anchors were never
written from one whose ledger was replaced by an empty one; both read `none`. To start a register over, use another
key or another project. The anchors of a register cannot be taken out of the ledger unnoticed: a commit that removes
`anchors/<key>/<project>.jsonl` is found, listed under the name of the register by `traceworks verify`, and fails
`verify` of a new register under the old key and project, which is what the anchors are for. That name is
`anchors.<length of the key>.<key>.<project>` (`anchors.5.hello.shop`): a key and a project may both hold dots, so
the length of the key keeps two registers from sharing a name, and a problem of a register is acknowledged under it
like one of a run (docs/cli.md, "verify"). The name is the name of a file in the ledger too, so one longer than
200 characters is written `anchors.sha256.<the SHA-256 of key/project>`; the sentence of each problem still names
the key and the project. The anchors an earlier version wrote under a key alone are listed as
`registers.<key>`, as they always were. `report.html` shows the chain's own verification line, not the anchor's.

**An acknowledgement covers one state of the register.** In `traceworks verify` without a run, the sentence of
every problem of a register that does not verify ends with the state of its log: `(the register's log ends with
the line <SHA-256>; its lines have the SHA-256 <SHA-256>)`, or `(the register's log has no line)`. An
acknowledgement covers a problem by its sentence, so a register whose log changes again after it was acknowledged
is reported again. The two problems that leave the log as it is say what they found themselves: `state.json
differs from the log: it was edited, or written by another version (state.json has the SHA-256 <SHA-256>)`, and
`seq 2: the blob findings_blob <name> does not match its name: it was edited in place (its content has the
SHA-256 <SHA-256>)`. So an acknowledgement covers one content of `state.json` or of an edited blob, and after
another edit the problem is listed again. A problem listed under a hashed name (`anchors.sha256.<...>`) begins
with `the register <key> of <project>: `, since the name does not say whose it is.

**A mismatch that stays is said again.** A register that does not match its anchors (a log that was cut and
written on) is not repaired by a later write: every decision, `render` and run on it says `the register does not
match its anchors in the ledger ('traceworks findings <key> verify --project <project>' says where)`, and
`verify` fails for it. An acknowledgement covers the state the log had then, so the problem is listed again after
the next write.

### A register whose log is gone

A register the ledger knows and whose log is not there is **gone**, for every run and every command, and nothing
is begun in its place. Gone means: the ledger holds anchors of the key and the project, and the register's log has
not begun. It is missing (the file, or the whole folder), empty, a fragment of a line, or holds notes of the tool
alone (`register.repaired`, `register.unanchored`).

```
$ rm registers/hello/shop/events.jsonl
$ traceworks run examples/audit-hello --workspace ~/work/shop --project shop
traceworks: The run is refused: the ledger holds anchors of the register hello of shop, and its log is gone: the
  register is not begun anew under the anchors of the one that was there. Put the register's folder back, or feed
  another project key (--project KEY). 'traceworks verify' says what the ledger holds of it; an acknowledgement
  there silences that check and begins nothing anew
$ traceworks findings
hello  blog  example  OPEN 1
hello  shop  refused: The ledger holds anchors of the register hello of shop, and its log is gone: ...
```

Without that, a run would be handed nothing and its report would start a new log at seq 0 under the old anchors,
and a decision or a `render` would write a new `state.json` over the old one.

| Command | With a register that is gone |
|---|---|
| `run` of the key on that project | refused before the run exists (exit 6). The feed of a run that was under way refuses too: its summary says `register hello of shop: not applied — the ledger holds anchors of the register hello of shop, and its log is gone: ...`, and the run keeps its exit code |
| `findings <key> list`, `show`, `items` | refused (exit 6) |
| `findings <key> accept`, `dismiss`, `reopen`, `render` | refused (exit 6); nothing is written, and `state.json` and `report.html` stay as they were |
| `findings` | lists the register as `<key>  <project>  refused: ...`, goes on to the others and exits 6 |
| `findings <key> verify` | not refused: it says what is wrong (`seq 4: the ledger anchors this line (c3a3076cb485), but the log is empty, so lines were cut from its end`), exit 8 |
| `traceworks verify` | lists the register: the same lines while its folder is there, and `the ledger anchors the register hello of shop, and this home holds no such register: its folder was removed or renamed` once it is not; exit 8 |

- **The two ways out** are in the message: put the register's folder back, or feed another project key. An
  acknowledgement in `traceworks verify` silences that check and begins nothing anew: the register stays refused.
- **The projects and the keys of `findings` are the folders and what the ledger anchors.** A project whose whole
  folder was removed is still a project of its key, and a key whose folder is gone is still listed. So the only
  project a key has never becomes another one, and `The register 'hello' has 2 projects (blog, shop)` can name a
  project without a folder.
- **Anchors that cannot be read refuse the same way.** What cannot be asked is not taken as "no anchors": `the log
  of the register hello of shop is not there, and the ledger cannot say whether it holds anchors of it (<why>): a
  register is not begun while that cannot be asked`.
- **A register from before projects that is gone** takes its whole key with it. When the ledger holds the anchors
  an earlier version wrote under the key alone (`registers/<key>.jsonl`), and the home holds neither that register
  nor a project whose log says that it was assigned from it (`register.assigned`), nobody can say which project
  those anchors were about. Then every run with the key is refused, for any project, and so are `assign` and every
  `findings` command on the key; only `findings <key> verify --project <project>` of a project whose folder is
  there still says what it finds. The overview lists the key in one line, `<key>  refused: ...`:

  ```
  traceworks: The ledger holds anchors of the register hello from before registers had projects, and this home
    holds neither that register nor one that was assigned from it: no register of hello is begun beside the
    anchors of the one that was there, for any project. Put the register's folder back, or feed a register of
    another key. 'traceworks verify' says what the ledger holds of it; an acknowledgement there silences that
    check and begins nothing anew
  ```

  The `blobs` folder such a register leaves behind is no project: a folder named `blobs` that holds blobs itself
  is not listed as one. A project of the key whose log cannot be read accounts for nothing: it does not stand for
  the register that was assigned, and the refusal names it, `... nor one that was assigned from it (the log of the
  register hello of shop cannot be read (Cannot read <home>/registers/hello/shop/events.jsonl: access denied), so
  it accounts for nothing): no register of hello is begun ...` (several projects are joined with `; `).
- **`verify` of a gone register has two exit codes.** `findings <key> verify` of a project's register that is gone
  says what is wrong and exits 8. For a register from before that is gone it is refused like every other command
  on the key (exit 6), unless the command means a project whose folder is there: the one `--project` names, the
  declared project of the working directory, or the only project the key has.
- **A register the ledger never anchored starts as ever**: a new project, or a home with a new ledger.

### A ledger that cannot be listed

Which projects a key has is known from the folders and from the ledger, and a project whose folder is gone is
known from the ledger alone. A ledger that is there and cannot be listed (one that cannot be read, or one this
version does not accept) therefore does not shrink the projects of a key to its folders: "the only project the key
has" is then not asked of the folders that are left.

```
$ traceworks findings
hello  shop  example  OPEN 1  DISMISSED 1
hello  refused: the ledger cannot say which projects the key has (The ledger <home>/ledger cannot be read: ...): a
  project whose folder is gone is not listed here, and a command on the key needs --project KEY
$ traceworks findings hello list
traceworks: The ledger cannot say which projects the register 'hello' has (The ledger <home>/ledger cannot be
  read: ...): name the project with --project KEY
```

| Command, with such a ledger | What it does |
|---|---|
| `findings` | lists the folders as ever, then one `refused:` line per key, and exits 6. Without any register folder: `refused: this home holds no register, and the ledger cannot say which registers it anchors (<why>)` |
| `findings <key> <command>` without `--project` | refused (exit 6), also when the working directory lies in a declared project that has a register of the key |
| `findings <key> <command> --project P`, the log of P has begun | as ever: the register is listed and shown. A decision is recorded and exits 6, since its anchor cannot be written ("Deciding") |
| `findings <key> <command> --project P`, no folder of P | refused (exit 6): `This home holds no register 'hello' of the project 'portal', and the ledger cannot say whether it anchors one (<why>)` |
| a key no folder has | refused (exit 6): `This home holds no register 'nokey', and the ledger cannot say whether it anchors one (<why>)`, where a ledger that can be listed makes it a usage error (exit 2) |
| a register whose folder is there without a log | refused, by the sentence of "A register whose log is gone": a register is not begun while that cannot be asked |
| `findings <key> verify --project P`, `traceworks verify` | fail (exit 8): the anchors cannot be compared. `findings <key> verify` without `--project` is refused like every other command (exit 6) |

## What a register does not do

It does not resolve a model's judgement by silence (a judged rule needs `gone`), normalise subjects, match catalog
keys fuzzily, let a finding inherit the disposition of an absent finding of another rule on the same file, replay an
invalid report as `previous_report`, or take the facts of the report block as evidence for its own findings.
docs/workflow-format.md has the rows and the reasons.

It does not know what a project is. The project key is the operator's word, as unsigned as the actor
(docs/security.md, "Registers"):

- **A wrong `--project` mixes two projects.** A run of the shop started with `--project blog` reads the blog's
  findings and decisions and resolves, in the blog's register, what the shop does not have. Nothing compares the
  content of the workspace with the key, and nothing in a workspace or a package can name a project. The `projects`
  setting narrows this to folders: with both projects declared, that run is refused. A setting that binds the
  wrong folder to a key mixes them all the same, and a folder that was moved without the setting changed is no
  declared folder any more: a run in it has no project.
- **Every run of one key and project must look at the same workspace.** A register is fed by full snapshots: a
  measured finding that a report does not state is gone ("Reconciliation"). A run in a subfolder of a declared
  project is of that project, sees only the subfolder, and resolves what it does not see; so does a run on
  another checkout or another branch under the same key. Traceworks does not check that two runs of one register
  looked at the same thing.
- **One workflow per key is checked, not locked.** The check reads the registers of the key that can be read, at
  the start of a run and again before the first report of a project. Two first runs of two workflows with the same
  key that start at the same moment can each create a register of that key for its own project.
- **An earlier version does not see a register of this layout** and refuses the ledger that anchors it ("A register
  from before projects").

It does not prove who decided. The actor of a decision is a label (docs/cli.md, "Actor"), and the agent beside it
is the name the caller gave ("Who decided"): self-declared and unsigned. An agent that leaves its name out is
recorded as the actor alone, any caller can give any name, and no setting lists who may decide. Decisions are not
signed (docs/security.md, "Registers").
