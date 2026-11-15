# Testing self-audit

Would the tests of Traceworks notice when the code breaks? A deterministic script measures what can be measured
about the tests without running them, and decides *which* classes are judged; an AI names, per class, a change of one
line that no test would notice; a script checks the AI's evidence, counts and sets the gate.

```
facts     (shell, scripts/facts.py)       measures the test inventory, chooses the classes -> testing-facts.json, candidates.jsonl
verdicts  (foreach, Claude, tools: read)  one call per class: a change of one line that no test notices
report    (shell, scripts/report.py)      checks every quoted line, writes the report      -> testing-report.md, the gate, the envelope
```

Why it is built this way:

- **Measured findings need no AI.** A class no test names, a test that asserts nothing, a disabled test and a command
  no integration test runs are facts of the sources. The report lists them from `testing-facts.json`, also when every
  AI call fails.
- **Coverage is the script's decision, not the model's.** The classes are chosen by fixed rules (below), the ones the
  cap cuts are named, and the record holds `candidates.jsonl`, so you can see what was judged. What the script could
  not measure it says as `NOT MEASURED: <what> (<why>)`.
- **The AI answers one question per call, and may read.** The class arrives in the prompt with the test files the
  script found for it, and the call runs with `tools: read`: the model reads those tests and may search the workspace
  for others, which every container sees read-only. It cannot change or run anything. It never counts and never
  sets the gate.
- **A finding counts only with evidence.** It quotes the line it would change; the report step checks that the quote
  is a line of code of the class's file (compared after trimming) and that it names a change and a reason. Every
  other AI finding is counted and shown as unverified, and is not in the register.
- **An answer cannot speak for another class.** Each line of `candidates.jsonl` carries a random token of the run that
  no prompt shows, so no model can write the marker of another class's answer; a marker that occurs twice leaves its
  class not judged. The code in the prompt sits between two lines with a random frame of the run. The `verdicts` step
  declares `untrusted_input`, so an instruction aimed at the model in the sources turns the gate YELLOW.
- **No model text starts a line of the report step's output.** The gate lines come first; a model's words are put on
  one line, behind a list marker; in the envelope `<` and `>` are written as escapes, so no text can end it.
- **No package image.** The scripts need only `python3` (standard library), which the built-in shell image has. They
  run without network, read the workspace read-only and write only to the scratch directory.

## Run it

From the root of a Traceworks checkout, which is the workspace the audit reads:

```bash
traceworks images build                      # once: the built-in images (shell, claude, egress)
traceworks check examples/self-audit/testing
traceworks describe examples/self-audit/testing
traceworks run examples/self-audit/testing
```

The `verdicts` step calls Claude, so it needs your Claude credentials (see "AI steps" in
[docs/cli.md](../../../docs/cli.md)). **It makes one call per chosen class**, one after the other: at most 30
(`candidates_max` in the rule file). The model is pinned (`claude-opus-5-5`), so a later run judges with the same
model.

`traceworks check` prints one line for this package and exits 0:

```
INFO    dated-model-id    verdicts (block 1): names the dated model id "claude-opus-5-5" instead of an alias ...
```

That is the pinned model, on purpose. Change it in `workflow.json` when the model is retired.

The run leaves its results in the scratch directory, and the record keeps them:

| File | What it holds |
|---|---|
| `testing-facts.json` | the source roots, the counts, the commands declared and run, every measured finding, what was not measured, the classes chosen, the ones that wait for a later run, and under `items` every class that could be judged with its digest, the state the register gave it and whether this run chose it |
| `candidates.jsonl` | one class per line: its item name and digest, file, size, branches, score, its test files, the numbered code the AI saw, and the run's token and frame (so the file differs from run to run in these two fields) |
| `testing-report.md` | the gate, what was measured, the measured findings, the verified changes with the line and the changed line, the low findings, the unverified AI findings, the classes not judged and judged, what a verdict without a finding says it tried, and the classes that wait for a later run |
| `findings.jsonl` | the findings of the envelope as data, one JSON object each: what the facts step measured, and for an uncaught change its class (`item`), the number the class has in this run (`number`), the line, the model's changed line and why no test notices. `traceworks findings testing show <id>` reads it from the record of the run ([docs/registers.md](../../../docs/registers.md), "The detail of a finding") |

**The gate follows the items.** Every class that could be judged is an item, and the report step says in what
state each is after the run:

| Gate | When |
|---|---|
| RED | an open finding of high severity stands: a verified change the verdict rates high, or an open high one of the register that this run did not answer as gone, also when its class got no place or its call failed |
| YELLOW | an item is open or not judged; or a finding was measured; or something was not measured |
| GREEN | every item is settled, nothing was measured as a finding and nothing was left unmeasured |

| An item is | When |
|---|---|
| clean | the fact step left it out as clean; or it was judged, with nothing for the register (a low finding is nothing for the register) |
| decided | the fact step left it out as decided; or it was judged with nothing new, and a person decided a finding of it or the answer said that one is gone |
| open | a new finding of medium or high, an open finding of the register that holds, or one that got no answer |
| not judged | no place under the cap, a failed call, or an answer with a finding that did not verify |

The gate description ends with the number that can reach its goal, in the same words in every self-audit:
`87 of 110 items settled (71 clean, 16 decided; 20 open, 3 not judged)`. The report has the same line. Where nothing
sets `{{ audit_items }}` and `{{ audit_findings }}`, nothing was known before and the same holds with what this run
judged. The severity is the only word of a model that reaches the gate, and only for a finding whose evidence holds.

## The register

The workflow is an audit (`metadata.audit`, key `testing`): the `report` step ends its output with the audit result
envelope, and the register `testing` under the home keeps the findings across runs
([docs/registers.md](../../../docs/registers.md)).

| Rule | Subject | Severity |
|---|---|---|
| `class-without-test` | the class, `pkg.Name` | MEDIUM in a layer the rule file weighs above 1, else LOW |
| `test-without-assertion` | `pkg.TestClass#method`, a nested class as `pkg.Outer.Inner#method` | MEDIUM |
| `disabled-test` | `pkg.TestClass#method`, or `pkg.TestClass` for a disabled class | LOW |
| `command-without-integration-test` | the command's name | MEDIUM |
| `uncaught-mutation` | `pkg.Name#<the first 12 hex digits of the SHA-256 of the quoted line, trimmed>` | MEDIUM or HIGH, as the verdict says; LOW only for an open finding that an answer states again as low |

No subject holds a line number, so a finding stays the same finding when its line moves. The evidence of a measured
finding names the fact the `facts` step stated (`class_without_test: <file> (<n> lines, <n> branches)`), and the
evidence of an `uncaught-mutation` names its class among the chosen ones (`candidate: <file> | <the quoted line>`);
the register checks both against the fact sheet of the run. The description of an `uncaught-mutation` holds the
changed line and the reason in the model's words, so `traceworks findings testing show <id>` is enough for triage.
`catalog.json` names the five rules and their severities.

The envelope is a full snapshot unless something was left unmeasured. Then it says `"snapshotComplete": false`,
the register rejects it (`report.rejected`) and no finding changes: a measured finding that was not looked for must
not count as resolved. A class without a verdict does not make the snapshot partial (below).

**The classes are items, and a run judges only what needs a verdict.** Every class that could be judged (it has a
test, and enough lines of code) is an item of the audit ([docs/registers.md](../../../docs/registers.md), "Items").
Its name is the name of the class. When two source roots hold a class of one name, one keeps the plain name and the
other is `<class>~<source root>`; the plain name stays with the class the register knows by it (the self-audit
README says how that is told). A name of more than 287 characters is cut and ends with a digest of the whole, so the
subject of a finding, the name with `#` and 12 hex digits, fits the 300 characters the register shows.
`facts.py` gives each a digest of what a verdict about it sees: the file of the class, every test file that names
it, and `verdict_version` of the rule file. The envelope lists all of them, with `judged: true` for a class whose
answer the report step read, and every `uncaught-mutation` names its class as `item`. The register keeps the digest
and the run of each class's last verdict, and the next run gets them back as `{{ audit_items }}`:

| What the register says of the class | What the next run does |
|---|---|
| `clean` (judged, nothing found) or `decided` (every finding accepted, dismissed or resolved), and the digest is the same | nothing: the class is not sent to the model |
| `not judged`, or unknown, or the digest differs: the class or one of its tests changed | judges it, the highest scores first |
| `open` and the digest is the same | asks about its findings again, in the places the classes above leave |

A class the register holds an open `uncaught-mutation` of counts as open whatever its row says: a finding of a run
before items existed names no item.

So a run costs at most `candidates_max` calls, the classes that are settled free their places for the next ones,
and over some runs every class is judged once. `traceworks findings testing` shows how far that is (`items 87 of
110 settled`), and `traceworks findings testing items` lists the classes. A class is asked about again when its
digest changes: fix a finding by adding a test, and the next run asks whether it is gone. Raise `verdict_version`
when the question changes, and every class is judged again. Where nothing sets `{{ audit_items }}` (an
older Traceworks), the placeholder stays as it is, no row is read and the run judges the highest scores as before.

**One failed call is one class not judged.** A call that timed out, failed or answered without JSON leaves its
class with `judged: false` in the envelope. So does an answer with a finding that did not
verify: the register never sees such a finding, and the item must not read clean because of it. The register applies the report, the class keeps the verdict it had,
and its open findings stay open, since no run said that they are gone. The gate stays YELLOW, and the next run
judges the class again.

**Low findings stay in the report of the run.** The prompt says what a severity means here: high when the change
silently breaks something Traceworks guarantees (a container flag or a mount check, a secret in the record, a record
that still verifies after it was changed, a wrong path, status or gate); medium for wrong behaviour a user would see
in normal use; low for an edge case or an error path. The register and the gate take medium and high. A new finding
the verdict rates low passes the same check and is listed in `testing-report.md` under "Low findings, in this report
only", and nowhere else: not in the envelope, not in `findings.jsonl`. Its class counts as judged, and can be clean.
An open finding the register already holds is answered as before, holds or gone, whatever its severity. Of the first
64 findings of this audit, 32 were rated low: a list of open findings that grows with attention has no end.

**"Nothing found" has reasons.** An answer without a finding names in `"tried"` up to three changes the model
considered and the test it says catches each. The report lists them as the model's words; nothing checks them.

**An uncaught change is not resolved by silence.** The four measured rules resolve at once when a snapshot no longer
states them. `uncaught-mutation` is a model's judgement (`"judged": true` in `catalog.json`), and the register keeps
such a finding open until a run says explicitly that it is gone
([docs/registers.md](../../../docs/registers.md), "Reconciliation"). The facts step gets `{{ audit_findings }}`, and
`facts.py --known` gives each line of `candidates.jsonl` the open findings of its class, each with an id (`K1`), and
the ones a person decided. The prompt holds only those, and the answer settles each open one in `"known"`:

| The answer | What the report step does | In the register |
|---|---|---|
| `{"id":"K1","status":"holds", file, line_quote, mutation, why_uncaught, severity}` | checks the line like a new finding's, and states the finding again under the subject the register knows | seen, stays `OPEN` |
| `{"id":"K1","status":"gone","reason":"..."}` | names the finding in the envelope's `gone`; the reason goes to the report | `RESOLVED` |
| none, an unknown status, or `holds` for a line that is not in the file | lists it under "Open findings without an answer"; the gate is at least YELLOW | stays `OPEN`, counted as not mentioned |

Without a model, the facts step names an open finding as gone when its class is no longer in the sources, or when
the line it quotes is no longer a line of the file (the digest in the subject proves the register shows the line
whole). An open finding of a class that the cap cut, or that is no longer chosen, is not asked about: it stays open,
and `traceworks findings testing show <id>` says how many reports have been silent about it. A dismissed or
accepted finding is decided: it is not asked about and not reported again.

**Triage of an `uncaught-mutation` is mechanical.** Make the change the finding names and run the tests of the
module. Green: the gap is real, and the fix is the test that goes red (`traceworks findings testing accept <id>`
until it exists). Red: the finding is refuted; dismiss it with the name of the test that caught it:

```bash
traceworks findings testing list
traceworks findings testing dismiss <id> --note "RedactorTest#aSecretAtTheEnd_isReplaced goes red"
```

## What the script measures

`scripts/facts.py` reads every Java file below the source roots of the rule file as text. A small scanner
(`scripts/javatext.py`) blanks comments and the contents of string and character literals first, so a word in a
comment, a Javadoc or a string is never code. Types, method declarations, calls and annotations are then found by
brace matching and regular expressions. It is no parser and resolves no types.

- **`class-without-test`.** A top-level type of the main sources with at least `class_min_ncloc` lines of code and
  `class_min_branches` branches (`if`, `for`, `while`, `case`, `catch`, `?:`, `&&`, `||`), for which the test sources
  hold no type called `<Name>Test`, `<Name>IT` or `<Name>Tests` and no file that names `<Name>` in its code. A plain
  `import` does not count as naming it. The two thresholds keep records, exceptions, interfaces and small value types
  out. A class that tests reach only through another class is still a finding: no test would name it in a failure.
- **`test-without-assertion`.** A method with one of the `test_annotation`s whose body calls no method with a name
  that matches `assertion_call` (`assert...`, `verify...`, `fail`, `expect...`), and calls no helper that does. A
  helper is a method of the test sources the call can reach: of the same file (also the outer class of a nested test
  class), of a class the test class extends, of a static import, of the type a call names (`Harness.check(...)`), or
  of the type the receiver is declared with in the file (`Harness tool; tool.check(...)`). Helpers of helpers count.
- **`disabled-test`.** `@Disabled` on a test or a test class, with its reason. A method that calls an assumption
  (`assume...`, `assumingThat`) is skipped, not failed, when the assumption does not hold; whether that is a finding
  too is the rule file's choice (`assumption_report finding` or `fact`). This package says `fact`: such tests are
  counted (`tests_that_may_skip_count`) and no findings, because here they are the tests that need a container
  runtime, and the integration job of the CI runs them where a missing runtime fails.
- **`command-without-integration-test`.** A command the command line declares (`command_declared`, on Traceworks
  `Spec.of("<name>", ...)` below `traceworks-cli`) that no call of a `command_runner` below `command_tests` has as its
  first string argument (`traceworks.exec("verify", id)`).

On Traceworks on 2026-11-12 it read 429 types and 4118 test methods in three seconds and found 18 classes no test
names, 1 test that asserts nothing, 19 tests that skip themselves on an assumption (most of them where no container
runtime works), and 3 commands no integration test runs (`credentials`, `describe`, `watch`). The test and the three
commands were true findings and are fixed; the 19 were none, which is why an assumption is a fact here now.

## How the classes are chosen

A class is eligible when at least one test file covers it by name and it has at least `candidate_min_ncloc` lines of
code; a class without any test is a `class-without-test` finding already, and every change of it would go unnoticed.
Interfaces are left out. The score is

```
(lines of code + score_per_branch * branches) * the weight of its layer
```

and the `candidates_max` highest scores are judged, the rest are named as cut. The layers of the rule file put first
where a missed bug costs most: the engine, the record, the container arguments and mount checks, the redaction. A
class reaches the prompt whole up to `max_code_lines` lines; of a longer one the first lines, and the prompt says so.
The prompt names up to `max_tests_listed` test files: the ones called like the class first, then the files that name
it most often.

## The rule file

`rules/testing.rules` holds every threshold; the scripts hold none. One `name value ...` per line, `#` starts a
comment line.

| Line | Meaning |
|---|---|
| `main_root <pattern>`, `test_root <pattern>` | directories of main and test sources below the workspace; `*` is a directory name or part of it, `**` any directories; several lines add up |
| `skip_dir <name> ...` | directories never entered (hidden ones never are) |
| `class_min_ncloc`, `class_min_branches` | from where a class without a test is a finding |
| `test_suffix <suffix> ...` | what a test type of a class is called after the class's name |
| `test_annotation <name> ...` | the annotations of a test method |
| `assertion_call <regex> ...`, `assumption_call <regex> ...` | names of methods, each matched as a whole |
| `disabled_annotation <name> ...` | the annotations that disable a test |
| `assumption_report finding\|fact` | whether a test that calls an assumption is a `disabled-test` finding or only counted |
| `command_sources`, `command_declared <regex>` | where commands are declared; group 1 of the expression is the name |
| `command_tests`, `command_runner <method> ...` | where the integration tests are, and the methods that run a command |
| `candidates_max`, `candidate_min_ncloc`, `score_per_branch` | the choice of the classes |
| `max_code_lines`, `max_tests_listed` | what of a class reaches the prompt |
| `max_findings_per_class` | how many findings the report accepts from one answer |
| `layer <weight> <package or class>` | the weight of a layer; the longest prefix counts, every other class weighs 1 |
| `severity <rule> <LOW\|MEDIUM\|HIGH\|CRITICAL>` | the severity of a measured finding; keep `catalog.json` in step |

An error in the rule file ends the step and names the entry.

## Limits

- The scanner reads text. It does not know which class a name resolves to: a test that names another class of the
  same simple name counts for both, and a helper is matched by its name within the types the call can reach. A test
  that asserts only through a helper of a library the sources do not hold is a finding.
- "No test names it" is not "no test reaches it". Triage decides whether a class needs a test of its own.
- The AI's change is a claim until someone makes it and runs the tests. The evidence check proves the line exists,
  not that the tests stay green. Real mutation testing as a deterministic step would need the JDK and an offline
  Maven repository in an image; it is not built.
- The severity is the model's word. A model that rates everything low hides nothing from the report of the run, but
  keeps it out of the register and away from the gate. Read the low findings of a run when their share looks wrong.
- Which files the model opens is its own choice, so two runs can differ in what was read, and in which lines they
  quote. A finding the model does not answer for stays open; one of a class that is no longer chosen stays open
  until a person dismisses it or the class is chosen again. A model with
  `read` can also find its own API key in the container ([docs/security.md](../../../docs/security.md), "A model
  with tools can read its own credential"); the record redacts it, and the model has no tool to send it anywhere.
- The rule file names Traceworks' own packages and directories (the layers, `command_sources`, `command_tests`). On
  another Java project, run it with `--workspace` and adapt the rule file.

## Tests

The scripts' tests are in [`examples/self-audit/tests-testing`](../tests-testing), a module of the Maven build, so
`mvn verify` runs them; they start `python3` on small source trees in temporary directories and make no AI call. One
of them runs a copy of the package through the real engine as a host run, with a shell loop in the AI's place, into
a register (it needs `bash`). On its own, run the module with `-am`:
`mvn -pl examples/self-audit/tests-testing -am test`. The integration suite runs the package as it ships, in
containers and against the fake Claude CLI (`SelfAuditTestingIT`, [integration-tests](../../../integration-tests)).
