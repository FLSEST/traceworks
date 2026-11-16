# Registers: findings across runs

A run is a record of one execution. A **register** is what an audit knows across its runs: the findings its reports
stated, which of them are new, still open, resolved, reopened or decided by a person, and when each was seen. It
is kept with the same means a run uses: an append-only, hash-chained log of plain
files, blobs, and derived files a reader can check against the log. There is no database and no service.

A register has three readers: the next run of the audit, which gets what the register knows as untrusted data
(`{{ audit_findings }}`, `{{ previous_report }}`); a person, through `traceworks findings` and `report.html`; and the
operator's hooks, which get the diff of each run (a TODO tool, an issue tracker).

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
| `key` | the register, `[a-z0-9][a-z0-9._-]{0,127}`. A register belongs to the workflow (by its `id`) whose run created it; a run of another workflow with the same key is refused at its start and writes nothing |
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
rule `todo_item` whose subject is its title; a reworded title is a new finding. A line has the limits of a finding
of an envelope: a title over 2000 or a description over 20 000 characters rejects the report. That is the simple form for a workflow whose findings are tasks (GitHub issues, Jira), not measurements.

`examples/audit-hello` is the smallest audit: two shell steps and no AI. `examples/self-audit/architecture` feeds
the register `architecture` with its measured findings and its verified verdicts. `examples/self-audit/security`,
`functionality` and `testing` feed registers of their own; `examples/self-audit/README.md` shows how the audits, the
registers and the triage of their findings make a loop.

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
  findings: a report holds at most 1000, and the next run is handed at most 250 rows of `{{ audit_findings }}`.
  Choose the finest subject a person would still decide on one by one.
- **The subject must stay the same while the thing does.** Nothing is normalised, so a subject that holds a line
  number, a count or a sentence a model wrote is a new finding after every change, and the decision made on the old
  one no longer applies. Names are stable (a path, a type, a method, a coordinate); what moves belongs in `title` and
  `evidence`.
- **Another level is a new start.** An audit that changes its subjects, from classes to methods, say, reports
  findings the register has never seen: the old ones resolve (those of a judged rule when the report names them as
  `gone`), and the new ones come without the decisions made on the old.

## What a run does to its register

At the end of every run of an audit workflow, after the run's own record is complete:

1. The run must have ended `COMPLETED` or `COMPLETED_WITH_ERRORS`, and the report step must have completed with
   an output. Otherwise the register records `report.rejected` with the reason, and no finding changes. A run is
   applied once. (A run whose own record does not hold feeds nothing: its completion refuses before the register is
   touched.) Before the run exists, its start check reads the register: a register that belongs to another workflow,
   or whose log cannot be replayed, refuses the run with a message that names `findings <key> verify`.
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

The run's summary ends with what happened:

```
  register architecture: 1 new, 3 resolved, 0 reopened, 17 open now
  register security: 0 new, 2 resolved, 0 reopened, 9 open now, 3 of them not mentioned by this report
  register testing: 2 new, 1 resolved, 0 reopened, 14 open now; items 87 of 110 settled, 29 judged in this run
  register architecture: not applied — the report step 'report' ended FAILED
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
| `name` | the item's name, stable across runs; one line, at most 300 characters, no control character, each name once. A `¦` is read as `|`, in a name and in a finding's `item`, before the names are compared: two names that differ in that only are one name, and the list is refused |
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

`traceworks findings` shows, for a register with items, the number that can reach its goal:

```
testing  testing  OPEN 14  DISMISSED 6  RESOLVED 71
  items 87 of 110 settled: 71 clean, 16 decided; 20 open, 3 not judged
```

`findings <key> items` lists them, what needs a look first (`--json` for a script: `name`, `state`, `digest`,
`verdict_digest`, `verdict`), and `report.html` has the same line and the list.

## What the next run gets

The next run of the audit gets three placeholders as untrusted platform data, handed to a shell line by reference like every
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

`run --inputs-from <run>` starts a run with the inputs an earlier run was started with, so an audit can be repeated
on the same inputs; `run.started` records `inputs_from`.

## Reading a register

`traceworks findings` lists the registers; `findings <key> list`, `show`, `items`, `verify` and `render` read one
(docs/cli.md, "findings"). `registers/<key>/report.html` is a self-contained page with the findings by status, the
history of the log and the verification line, written after every applied or rejected report and after every
decision.

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

`accept` and `dismiss` take an `OPEN` or `AWAITING_VERIFICATION` finding and need a note; `accept` freezes the
finding's evidence for the comparison with later reports and sets a review date (`--until`, at most 180 days ahead,
which is the default). `reopen` takes an accepted, dismissed or resolved finding. A wrong transition is refused and
records nothing. Every decision is a line of the log with the actor (docs/cli.md, "Actor"), the time, the note and
the state after it; `signature` is `null` until signed decisions exist.

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
{"hooks": {"register_changed": ["/path/to/register-todos.sh"], "timeout_seconds": 60}}
```

It is started without a shell, with the operator's own environment, in the register's folder, after the register's
lock is released, and gets the diff as JSON on its standard input:

```
{"format": 1, "register": "architecture", "workflow_id": "...", "run_id": "...", "report_valid": true,
 "new": [...], "resolved": [...], "reopened": [...], "review_due": [...], "still_open": [...],
 "counts": {"new": 1, "resolved": 3, ...}}
```

Each finding carries `id`, `rule_key`, `subject`, `severity`, `title`, `description`, `evidence`, `tags`,
`status`, `unverified_evidence`, `occurrences` and `regressions`; a resolved or reopened one its `reason`
(`regression`, `seen_again`, `worse: ...` for an accepted risk whose evidence got worse, and `higher_severity: LOW →
CRITICAL` for an accepted or dismissed finding stated above the severity its decision was made on). The
hook's exit code, duration and the tail of its standard error are recorded as `hook.ran`; a timeout is exit 124, a
program that does not start 127; the summary says `hook ran` or `hook exited N`. A failing hook never changes the
run's exit code. `examples/hooks/todo-service.sh` posts the diff to a TODO service over HTTP; the
same shape serves GitHub Issues or Jira. A workflow never configures a hook: the diff of a run is the operator's to
act on.

## The register folder

The registers lie in `<home>/registers/`, or where `registers_dir` in `traceworks.json` puts them (docs/cli.md,
"Places of the runs, the ledger and the registers"). A register names a run by its id, so it can be moved.

```
<home>/registers/<key>/
  events.jsonl     the log, hash-chained like a run's (docs/events.md, "The register log")
  blobs/<sha256>   the report texts, the findings and the items each report stated, and the state after every change
  state.json       the state folded from the log, as sorted compact JSON: the same state gives the same bytes; the
                   key items, and item of a finding, only for a register whose audit lists items
  report.html      the page
  register.lock    taken by every writer (an audit run, a decision), so writers of every process take turns
```

A reader takes no lock and reads the log, never `state.json`; `findings <key> verify` checks the chain of the log
(seq, prev, the key of every line), that every blob a line points to exists and hashes to its name, that the state
each applied report and each decision recorded equals the fold of the log up to there, and that `state.json` equals
the final fold. One changed byte of a line breaks the chain from the next line on; a blob edited in place no longer
matches its name; a state edited by hand differs from the fold. A writer that was cut off mid-line leaves a torn
tail, which the next writer discards and records as `register.repaired`.

### The anchor

The chain of a log says that no line before the last was changed. By itself it cannot say that the last line is the
one that was written (a `hook.ran` or a `finding.new` names no state), or that no line was cut from the end and
`state.json` written again. So every write of a register anchors its head outside the register's folder, in the
ledger (docs/events.md, "The ledger"): after a report was applied or rejected, after the hook's line, after a
decision and after `findings <key> render`, the ledger gets one commit that adds the line

```
{"seq":17,"head":"<the SHA-256 of line 17>"}
```

to `registers/<key>.jsonl` of the ledger (`<home>/ledger/`, or where `ledger_dir` puts it; the register itself may lie
where `registers_dir` puts it). The ledger keeps every anchor, not only the last. `findings <key> verify` reads
them and checks each against the log: the line with that `seq` must exist and hash to that `head`.

| What was done to the register's folder | What `verify` says |
|---|---|
| the last line was changed | `seq 17: the line is not the one the ledger anchors`, exit 8 |
| lines were cut from the end, also when `render` wrote `state.json` again | `seq 17: the ledger anchors this line, but the log ends at seq 15`, exit 8 |
| lines were cut and a later write put other lines there | the old anchor of that `seq` no longer matches, exit 8, from then on |
| nothing | `anchor    ok (seq 17 is the head the ledger holds, 9 anchors)` |

The anchor is written under the register's lock, after the register's own files, so the anchors of two writers are
in the order of their lines; the ledger's lock serialises them with the runs that end at the same time.

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
  `not anchored in the ledger (<why>)`, a decision or a `render` prints a warning on standard error, and `verify` says
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
written from one whose ledger was replaced by an empty one; both read `none`. To start a register over, remove its
folder and `registers/<key>.jsonl` in the ledger with a commit; an old anchor that stays would fail `verify` of the
new register, which is what it is for. `report.html` shows the chain's own verification line, not the anchor's.

## What a register does not do

It does not resolve a model's judgement by silence (a judged rule needs `gone`), normalise subjects, match catalog
keys fuzzily, let a finding inherit the disposition of an absent finding of another rule on the same file, replay an
invalid report as `previous_report`, or take the facts of the report block as evidence for its own findings.
docs/workflow-format.md has the rows and the reasons.
