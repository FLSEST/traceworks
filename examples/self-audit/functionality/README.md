# Functionality self-audit

Does Traceworks do what its documentation says? A deterministic step compares the documentation with the code where
that can be measured, and chooses the sections of [docs/cli.md](../../../docs/cli.md); an AI judges one section per
call; a step checks the AI's evidence, counts, sets the gate and feeds the register `functionality`
([docs/registers.md](../../../docs/registers.md)).

```
facts     (shell, python3)                measures docs against code, chooses the sections -> facts.json, sections.jsonl
                                          that need a verdict
verdicts  (foreach, Claude, tools: read)  one verdict per section, its text in the prompt
report    (shell, python3)                checks every quote, writes the report, the gate   -> functionality-report.md
                                          and the audit result envelope
```

Why it is built this way:

- **What can be measured is measured.** Whether a command has a section, an option is documented, an exit code is in
  the table: a script compares that, the same way in every run, and no model is asked.
- **What was not measured is said.** A document that is missing, a declaration the script cannot read: each is a
  line `NOT MEASURED: <what> (<why>)` in the fact sheet and the report, and keeps the gate from GREEN. So does a
  section without a readable answer, and one that waits for a later run because the cap was reached: the report
  lists both, and the fact sheet names the second as `NOT MEASURED: the section <id> (beyond max_sections ...)`.
- **The AI judges one section per call** and may read the workspace (`tools: read`), which every container sees
  read-only, to follow a command to the code that implements it. It cannot change or run anything. It never counts,
  never sets the gate and never writes the envelope.
- **A finding of the AI counts only with evidence that holds.** It quotes the sentence of the section and one line
  of code; the report step checks both against the files. A finding whose quotes do not hold is counted and shown as
  unverified, and is not in the envelope, so the register never sees it.
- **No words of the model reach the register.** A finding's title and evidence are the two quotes, which are text of
  the repository; what the model says is wrong stays in `functionality-report.md` and `findings.jsonl`. The gate
  lines come first in the step's output, and the envelope last.
- **The register is asked back.** The fact step gets `{{ audit_findings }}` and sorts it to the sections. The
  prompt of a section holds its own open findings, each with an id, and wants an answer for each (it still holds,
  or it is gone), and the findings a person decided, which are not reported again.
- **A run judges what needs a verdict.** Every section is an item of the audit. The fact step gets
  `{{ audit_items }}` and leaves out a section that was judged, is settled and has not changed since ("The
  register", below).

## Run it

From the root of a Traceworks checkout, which is the workspace the audit reads:

```bash
traceworks images build examples/self-audit/functionality    # once: the shell and claude images
traceworks check examples/self-audit/functionality
traceworks run examples/self-audit/functionality
traceworks findings functionality list
```

The `verdicts` step calls Claude, so it needs your Claude credentials (see "AI steps" in
[docs/cli.md](../../../docs/cli.md)). **It makes one call per section that needs a verdict**, one after the other:
34 calls on Traceworks in a first run when this package was written, at most 40 (`max_sections`), and in a later
run only the sections that changed or have an open finding.

`traceworks check` prints one line, and exits 0:

```
INFO    dated-model-id    verdicts (block 1): names the dated model id "claude-opus-5-5" instead of an alias ...
```

The model is pinned on purpose, so a later run judges with the same model. Change it in `workflow.json` when the
model is retired.

The run leaves its results in the scratch directory, and the record keeps them:

| File | What it holds |
|---|---|
| `facts.json` | the measured findings, what was not measured, the counts, the sections that wait for a later run, and under `items` every section with its item name, its digest, the state the register gave it, whether this run chose it and whether it was left out as settled |
| `sections.jsonl` | one section of this run per line: id, item name and digest, document, heading, part, lines, where to start reading and the files named there, the text the AI saw, and the run's frame and mark (so the file differs from run to run in these two fields) |
| `functionality-report.md` | the gate, the counts, what was not measured, the measured findings, the verified contradictions with what the model says is wrong, the low findings, and the unverified findings with the reason |
| `findings.jsonl` | the findings of the envelope as data, one JSON object each: what the fact step measured, and for a contradiction its section as the item of the envelope (`item`), the id the section has in this run (`number`), the two quotes and what the model says is wrong. `traceworks findings functionality show <id>` reads it from the record of the run ([docs/registers.md](../../../docs/registers.md), "The detail of a finding") |

**The gate follows the items.** Every section the AI could judge is an item, and the report step says in what
state each is after the run:

| Gate | When |
|---|---|
| RED | an open finding of high severity stands: a contradiction the verdict rates high, or an open high one of the register that this run did not answer as gone, also when its section got no place or its call failed |
| YELLOW | an item is open or not judged; or a finding was measured; or something was not measured; or there is no section at all |
| GREEN | every item is settled, nothing was measured as a finding and nothing was left unmeasured |

| An item is | When |
|---|---|
| clean | the fact step left it out as clean; or it was judged, with nothing for the register (a low finding is nothing for the register) |
| decided | the fact step left it out as decided; or it was judged with nothing new, and a person decided a finding of it or the answer said that one is gone |
| open | a new contradiction of medium or high, an open finding of the register that holds, or one that got no answer |
| not judged | no place under the cap, a failed call, or an answer with a finding that did not verify |

The gate description ends with the number that can reach its goal, in the same words in every self-audit:
`87 of 110 items settled (71 clean, 16 decided; 20 open, 3 not judged)`. The report has the same line. Where nothing
sets `{{ audit_items }}` and `{{ audit_findings }}`, nothing was known before and the same holds with what this run
judged.

## What the fact step measures

`scripts/facts.py` reads the documents as markdown (a heading inside a code fence is no heading) and the Java files
below every `src/main/java` as tokens, so a comment or a text block declares nothing. It does not compile anything.

| Rule | A finding when | Subject |
|---|---|---|
| `undocumented-command` | the main class creates a command class whose `Spec.of("<name>", ...)` has no `###` section under `## Commands` | `<command>` |
| `unknown-command-in-docs` | a `###` section under `## Commands` names a command the main class does not dispatch | `<command>` |
| `undocumented-option` | a `.flag(...)`, `.option(...)` or `.repeated(...)` of a command's `Spec` is not named as `--<option>` in the command's section | `<command> --<option>` |
| `unknown-option-in-docs` | a command's section names a `--<option>` that no command declares, that is no global option and that the rule file does not list as another program's | `<command> --<option>` |
| `exit-code-mismatch` | a `static final int NAME = N;` of `ExitCodes` has no row in the table under `## Exit codes`, or a row has no constant | `exit <N>` |
| `example-not-mentioned` | a directory below `examples/` with a `workflow.json` is named by no document | the package path |
| `missing-example-path` | a document names a path `examples/...` that does not exist | the path |
| `undocumented-block-type` | a type that a registered executor's `supportedTypes()` returns is in no block document as `` `type` `` | `<type>` |
| `unknown-block-type-in-docs` | a block document names a `` `type` block `` that no registered executor runs | `<type>` |

The first seven are the rules of the design; the two block type rules were added so that the comparison of block
types has a finding of its own.

What these rules mean in detail:

- A section heading such as `approve and answer` documents both commands; an option either of them declares may be
  named there.
- An option of another command, named in passing, is no finding: `unknown-option-in-docs` is an option no command
  has. The global options are the `--name` literals of the main class and of the class that declares `Spec`.
- The documents are `README.md`, `docs/*.md` and every `README.md` below `examples/`. A package's own README does
  not count for `example-not-mentioned`: a package only its own README names is not found by a reader.
- The block documents are `docs/cli.md` and the `block_doc` lines of the rule file.
- A side that could be read only in part proves nothing about what it lacks. When an executor's types cannot be
  read, no document is said to name an unknown type; when a block document is missing, no type is said to be
  undocumented. Both are reported as `NOT MEASURED`.

The fact sheet on standard output (`## FACT SHEET functionality`) has one `FACT func_<count>:` line per count, one
`HIT func_<rule>:` line per finding and one `HIT func_section:` line per chosen section, and
`FACT func_items:` and `FACT func_items_settled:` count the items and the ones left out as settled. The evidence
of every finding in the envelope names one of them, so the register marks none as unverified.

## The sections the AI judges

One item per `###` section under `## Commands` and per other `##` section of `docs/cli.md`, with its deeper
sections. A section longer than `max_section_chars` is judged in parts, cut at empty lines outside a code fence
(`cli.md#ai-steps/1`, `cli.md#ai-steps/2`). At most `max_sections` items are judged in one run; the rest wait for
a later run, and the report lists them. A section the register calls settled is not among them ("The register").

The answer of one call is one JSON object:

```json
{"section": "cli.md#run", "findings": [{"doc_quote": "...", "file": "...", "code_quote": "...", "issue": "...",
 "severity": "high|medium|low"}]}
```

`scripts/report.py` reads the output of the `foreach` (each item as `[<item>]: ` followed by its answer). A section
is **not judged** when it has no answer, no JSON object with a `section`, an answer for another section, no list of
findings, or when its marker occurs twice (each line of `sections.jsonl` carries a random mark of the run that no
prompt shows, so an answer cannot write the marker of another section). A finding is **verified** when all of this
holds:

- `doc_quote` has at least 20 characters and is in the text of the section the call judged, compared with every run
  of white space as one space, so a sentence the document wraps over two lines can be quoted;
- `file` is a regular file inside the workspace (after resolving links and `..`), and no `.md` file: a document
  cannot be the code that contradicts a document;
- `code_quote` has at least 8 characters and equals one line of `file` after trimming both;
- `severity` is `high`, `medium` or `low`.

The check is mechanical. It proves that both quotes exist, not that they contradict each other: that is what triage
is for.

A section whose answer held a finding that did not verify is listed for the register as not judged, like a section
without an answer: the register never sees such a finding, and the section must not read clean because of it.

## The register

`metadata.audit` feeds the register `functionality`; `catalog.json` names the ten rules and their severities. A
verified finding of the AI is the rule `docs-contradict-code` with the subject
`<document>#<section heading>:<first 12 hex digits of the SHA-256 of doc_quote>`, so it stays the same finding when
lines move in the document or in the code, and when another line of code is quoted for the same sentence. A reworded
sentence is a new finding.

**A contradiction is not resolved by silence.** The nine measured rules resolve at once when a snapshot no longer
states them. `docs-contradict-code` is a model's judgement (`"judged": true` in `catalog.json`), and the register
keeps such a finding open until a run says explicitly that it is gone
([docs/registers.md](../../../docs/registers.md), "Reconciliation"). So `facts.py --known` gives each line of
`sections.jsonl` the open findings of its section (of the parts that hold the quoted sentence, or of every part
when none does), each with an id (`K1`), and the answer settles each in `"known"`:

| The answer | What the report step does | In the register |
|---|---|---|
| `{"id":"K1","status":"holds", doc_quote, file, code_quote, issue, severity}` | checks both quotes like a new finding's, and states the finding again under the subject the register knows, whatever sentence is quoted now | seen, stays `OPEN` |
| `{"id":"K1","status":"gone","reason":"..."}` from every part that was asked | names the finding in the envelope's `gone`; the reason goes to the report | `RESOLVED` |
| none, an unknown status, or `holds` with a quote that does not hold | lists it under "Open findings without an answer"; the gate is at least YELLOW | stays `OPEN`, counted as not mentioned |

An open finding of a section that is no longer in the document is named as gone by the fact step itself. Only
`OPEN` and `AWAITING_VERIFICATION` findings are asked about; a dismissed or accepted one is decided.

The envelope is a full snapshot (`snapshotComplete: true`) unless something was left unmeasured. Then it says
`false`, and the register rejects the report and changes nothing: applying it would resolve measured findings the
run never looked for. The run's summary then says `register functionality: not applied`, and the report names what
was missing. A section without a verdict does not make the snapshot partial (below).

**The sections are items, and a run judges only what needs a verdict.** Every section the AI could judge, each part
of a long one, is an item of the audit ([docs/registers.md](../../../docs/registers.md), "Items"). Its name is
`<document>#<section heading>`, as a finding's subject starts, with `/<part>` for a section judged in parts and
`~2` for a second section of the same name. `facts.py` gives each a digest of what a verdict about it sees:
`verdict_version` of the rule file, the heading and the text of the section, and the content of the files the
prompt names as the place to start, the ones that declare the section's commands. Its lines in the document are no
part of it, so a section that only moved keeps its digest. The envelope lists every item, with `judged: true` for a
section whose answer the report step read, and every `docs-contradict-code` names the item of the section whose
verdict stated it. The register keeps the digest and the run of each item's last verdict, and the next run gets
them back as `{{ audit_items }}`:

| What the register says of the section | What the next run does |
|---|---|
| `clean` (judged, nothing found) or `decided` (every finding accepted, dismissed or resolved), and the digest is the same | nothing: the section is not sent to the model |
| `not judged`, or unknown, or the digest differs: the section or a file of its command changed | judges it, in the order of the document |
| `open` and the digest is the same, or an open finding of the section is in `{{ audit_findings }}` | asks about its findings again, in the places the sections above leave (`max_sections`) |

A section the register holds an open `docs-contradict-code` of counts as open whatever its row says: a finding of
a run before items existed names no item.

So a first run judges every section, and a later run the ones that changed since. `traceworks findings
functionality` shows how many items are settled, and `traceworks findings functionality items` lists them. Raise
`verdict_version` when the instructions or the prompt change what is asked, and every section is judged again.
Where nothing sets `{{ audit_items }}` (an older Traceworks), the placeholder stays as it is, no row
is read and every section is judged, as before.

**Low findings stay in the report of the run.** The instructions say what a severity means here: high when a user
who follows the section loses data, gets a wrong record, or is told something is safe or refused that is not; medium
when a command, an option, a default or an exit code behaves differently from what the section says; low for a
difference that misleads without changing what a user does (a count, a name, an order). The register and the gate
take medium and high. A new contradiction the verdict rates low passes the same check and is listed in
`functionality-report.md` under "Low findings, in this report only", and nowhere else: not in the envelope, not in
`findings.jsonl`. Its section counts as judged, and can be clean. An open finding the register already holds is
answered as before, holds or gone, whatever its severity. The measured rules are not touched: an undocumented
option is LOW and stays in the register, because a tool counted it.

**One failed call is one section not judged.** A call that timed out, failed or answered without JSON, and a
section that got no place under the cap, leave their item with `judged: false` in the envelope. So does an answer
with a finding that did not verify. The register applies the report, the section keeps the verdict it had, and its
open findings stay open, since no run said that they are gone. The gate stays YELLOW, and the next run judges the
section again.

Triage of a new finding: read the sentence and the line, and reproduce the difference with the command where that
is possible. Confirmed: fix the code or the sentence. Refuted:
`traceworks findings functionality dismiss <id> --note "<why>"`; the next run shows the model the decision, and
does not ask about the finding again. `traceworks findings functionality show <id>` prints what the model said is
wrong, from the run that last reported the finding.

## The rule file

`rules/functionality.rules`, one `key value` per line; `#` starts a comment line, and an unknown key is refused.

| Key | Meaning |
|---|---|
| `cli_doc` | the document of the command line (`docs/cli.md`) |
| `commands_heading`, `exit_codes_heading` | the `##` sections with the commands and with the exit code table |
| `exit_codes_class` | the class with the exit code constants |
| `doc` | one more document for the example paths, as a glob relative to the workspace; repeatable |
| `block_doc` | one more document for the block types; repeatable |
| `max_sections`, `max_section_chars` | the caps of the AI part |
| `verdict_version` | the version of the question a verdict answers, part of every section's digest: raise it, and every section is judged again |
| `foreign_option` | an option of another program that a command's section names, such as Docker's `--filter`; with a second word (`foreign_option from COPY`) only where that word stands right before the option, with nothing but a backtick, a quote, `[` or white space between, so that the same name anywhere else is still a finding; repeatable, each with its reason in a comment |
| `not_a_block_type` | a word that a document uses as `` `word` block `` and that is no block type; repeatable |
| `example_exclude` | a glob of directories below `examples/` that are no example packages; repeatable |

## Limits

- The fact step reads how Traceworks declares things today: `Spec.of(...)` with `.flag`, `.option` and `.repeated`,
  the command classes the main class creates, `static final int` constants, `supportedTypes()` returning string
  literals or constants of the same file. A declaration in another form is reported as `NOT MEASURED`, not guessed.
- An option is compared by its name. Whether the section describes it correctly is the AI's part.
- The AI sees one section and reads what it chooses to read, so two runs can differ. A contradiction it misses is
  missed; the evidence check only removes findings whose quotes do not hold.
- The severity is the model's word. A model that rates everything low hides nothing from the report of the run, but
  keeps it out of the register and away from the gate. Read the low findings of a run when their share looks wrong.
- Only `docs/cli.md` is judged by the AI. The other documents are covered by the example path and block type rules
  alone.
- The digest of a section holds its text and the files that declare its commands, not everything the model may
  read. A section is not judged again because code changed that those files only call: when the engine behind a
  command changes what the section promises, raise `verdict_version`, or wait until the section or the command
  file changes.

## Tests

[`examples/self-audit/tests-functionality`](../tests-functionality) is a module of the Maven build, so `mvn verify`
runs it. Its tests run the two scripts with `python3` on small source trees and read their output with the engine's
gate parser and the register's envelope parser; one of them runs the package's shape through the real engine and
the real register as a host run, with a shell step in place of the model (it needs `bash`). They make no AI call. On
its own: `mvn -pl examples/self-audit/tests-functionality -am test`.
