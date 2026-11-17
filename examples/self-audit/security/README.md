# Security self-audit

Traceworks audits its own sources against its threat model ([docs/security.md](../../../docs/security.md)). A
deterministic tool decides *which* code is judged; an AI judges only that code; a tool checks the AI's evidence.

```
areas     (shell, ./images/areas)   finds the areas by resolved type          -> areas.jsonl, areas-summary.json
verdicts  (foreach, AI, tools: read) one verdict per area, its code in the prompt; the model is the run's pick
report    (shell, ./images/areas)   checks the evidence, writes the report     -> security-report.md, the gate
```

Why it is built this way:

- **Coverage is the tool's decision, not the model's.** The record holds `areas.jsonl`, so you can see what was
  judged, and the report names every area that was not.
- **The AI starts from the area and may read on.** The area's code arrives in the prompt, and the call runs with
  `tools: read`: the model can read and search the workspace, which every container sees read-only, so it can follow a
  call to the code that settles a doubt. It cannot change anything and cannot run anything. The first real run
  (2026-11-11) judged with `tools: none`, and of 13 findings checked by hand 8 were refuted by code outside their
  area, the only high one among them: a check the area called was already there.
- **What reading costs.** The prompt is the same in every run, but which files the model opens is its own choice, so
  two runs can differ in what was read. A model with `read` can also find its own API key in the container
  ([docs/security.md](../../../docs/security.md), "A model with tools can read its own credential"); the record
  redacts it, and the model has no tool to send it anywhere. The audited code is still untrusted input: the step
  declares `untrusted_input`.
- **A finding counts only with evidence.** It must quote a line inside the area it judged; the report step checks that
  and lists every other finding as unverified.
- **An answer cannot speak for another area.** Each line of `areas.jsonl` carries a random token of the run that no
  prompt shows, so no model can write the marker of another area's answer; a marker that occurs twice leaves its area
  not judged. The code in the prompt sits between two lines with a random frame of the run, which the audited code
  cannot close. The `verdicts` step declares `untrusted_input`, so an instruction aimed at the model in the code turns
  the gate YELLOW.
- **No model text reaches a gate line.** The report step prints its gate first, shows only the rule ids of the rule
  file, and escapes every name from the workspace on standard output and in the report.

## Run it

From the root of a Traceworks checkout, which is the workspace the audit reads:

```bash
traceworks images build examples/self-audit/security    # once: the shell, claude, pi, egress and areas images
traceworks check examples/self-audit/security
traceworks run examples/self-audit/security                       # judged by Claude Fable 5.2, the default
traceworks run examples/self-audit/security --input judge=opus    # judged by Claude Opus 5.5
traceworks run examples/self-audit/security --input judge=astra   # judged by Astra 6, through Pi
```

**It makes one call per area**, one after the other: 110 calls on Traceworks in November 2026, for a first run. Each
model is named by its id, so a later run with the same pick asks the same model. Change an id in `workflow.json` when
its model is retired.

**A run needs the credential of the provider it picked, and of no other**: a credential of Claude
(`ANTHROPIC_API_KEY` or `CLAUDE_CODE_OAUTH_TOKEN`) for `fable` and `opus`, `OPENAI_API_KEY` for `astra`, each stored
with `traceworks credentials set <NAME>` (see "AI steps" in [docs/cli.md](../../../docs/cli.md)). The start check
knows the pick: a run judged by Claude needs no `pi` image and no OpenAI key, and a run judged by Astra nothing of
Claude. `images build` builds the images of every option, since it does not know which one a run will pick.

## The model that judges

The package opens one model choice, `judge`, and its one verdict step refers to it (`"model_choice": "judge"`). Who
starts a run picks an option with `--input judge=<option>`; without the input the default judges:

| `--input judge=` | Provider | Model |
|---|---|---|
| `fable` (the default) | `CLAUDE` | `claude-fable-5-2` |
| `opus` | `CLAUDE` | `claude-opus-5-5` |
| `astra` | `PI` | `openai/gpt-6-astra` |

- **The package decides what can be picked.** The options stand in `model_choices` of `workflow.json`
  ([docs/cli.md](../../../docs/cli.md), "Model choices"). A value that is none of them refuses the run before it
  exists, with the options listed. `traceworks describe` shows the choice before a run, and a seal of the package
  covers the default and the list: an input picks among the options that were sealed and cannot bring another model.
  The operator can narrow the list for a home with `allowed_models` in `traceworks.json`.
- **Another judge** is another option: add it to `model_choices.judge.options` with its `provider` and `model`.
- **The run says who judged.** Traceworks hands the steps the pick as the variables `{{ judge }}`,
  `{{ judge_provider }}` and `{{ judge_model }}`; the `areas` step passes them to the tool, which reads no
  declaration itself. The report starts with `**Judged by:** provider ..., model ...`, and every place of a finding in
  `findings.jsonl` carries `judge_provider` and `judge_model`, which `traceworks findings security show <id>` prints,
  from the run that last reported the finding. `traceworks show <run>` names the picked option (`Model     judge =
  ...`) and the provider and model of every single call, and `traceworks runs --long` lists the models of each run.

**One register, whoever judges.** The register `security` knows a finding by its rule and its area. The model is
not part of that, nor of the digest of an item, nor of anything else the envelope holds. So a finding one model
reported and another states again is the same finding, seen again, and its decision holds for every judge. Were the
model part of the identity, every change of the judge would resolve half the register and report it anew.

What follows from it: **a verdict of one model settles an area for all of them.** A run judges only what needs a
verdict ("The register", below), so after a first run with `fable`, a run with `astra` is asked about the areas that
are open, changed or were not judged, and about nothing the first judge found clean. A second model in the same
register is the next round of the same loop, not a second opinion. The register does not say which model settled an
item; it names the run of the item's last verdict, and that run's record names the model.

For a second opinion on everything, give the second judge a register of its own, in another home:

```bash
traceworks credentials set OPENAI_API_KEY --home ~/.traceworks-astra          # a home has its own credentials.json
traceworks run examples/self-audit/security --input judge=astra --home ~/.traceworks-astra
traceworks findings security list --home ~/.traceworks-astra                  # next to: traceworks findings security list
```

That home knows nothing, so every area is judged, and the register of the first judge stays as it is. The price is
that the decisions do not travel: a finding dismissed in one home is reported in the other. Take what the second
judge found and the first did not to the code, as in every triage, and fix it or leave it. The other way to have
everything judged again is `--verdict-version` in `workflow.json`: raise it and every digest changes, in the same
register and with every decision kept; that is for a changed question, and it holds for every later run. The key of
the register is no input: `metadata.audit.key` is a literal (a path segment, so no placeholder), and a key per model
would split the memory the same way as a home per model, inside one home.

The run leaves its results in the scratch directory, and the record keeps them:

| File | What it holds |
|---|---|
| `areas.jsonl` | one area per line: its item name and digest, file, member, lines, rules, hits, the numbered code the AI saw, and the run's token and frame (so the file differs from run to run in these two fields) |
| `areas-summary.json` | files read and not read, unresolved references, hits per rule, areas, areas cut by the cap, under `judge` the option, the provider and the model the run picked, and under `items` every area with its item name, its digest, the state the register gave it and whether this run chose it |
| `security-report.md` | the gate, who judged, the coverage, the verified findings, the low findings, the unverified ones, and every gap |
| `findings.jsonl` | the verified findings as data, one JSON object per finding of the envelope: the item of its area (`item`), the number the area has in this run (`number`), the file, the line and the quote, the model's issue and fix, and the provider and the model that judged (`judge_provider`, `judge_model`). `traceworks findings security show <id>` reads it from the record of the run ([docs/registers.md](../../../docs/registers.md), "The detail of a finding") |

**The gate follows the items.** Every area is an item, and the report step says in what state each is after the
run:

| Gate | When |
|---|---|
| RED | an open finding of high severity stands: a verified high one, or an open high one of the register that this run did not answer as gone, also when its area got no place or its call failed |
| YELLOW | an item is open or not judged; or a file was not read; or no Java file was read at all |
| GREEN | every item is settled and every file was read |

| An item is | When |
|---|---|
| clean | `find` left it out as clean; or it was judged, with nothing for the register (a low finding is nothing for the register) |
| decided | `find` left it out as decided; or it was judged with nothing new, and a person decided a finding of it or the answer said that one is gone |
| open | a new finding of medium or high, an open finding of the register that holds, or one that got no answer |
| not judged | no place under the cap, a failed call, or an answer with a finding that did not verify, whatever its severity |

The gate description ends with the number that can reach its goal, in the same words in every self-audit:
`87 of 110 items settled (71 clean, 16 decided; 20 open, 3 not judged)`. The report has the same line. Where nothing
sets `{{ audit_items }}` and `{{ audit_findings }}`, nothing was known before and the same holds with what this run
judged.

## The register

The workflow is an audit (`metadata.audit`, key `security`): the `report` step ends its output with the audit
result envelope, and the register `security` under the home keeps the findings across runs
([docs/registers.md](../../../docs/registers.md)). The envelope carries the verified findings. Those of one area and
one rule are one finding, because a register counts a rule and a subject once per report: the rule key is the rule
of the rule file (or `other`), the subject is the area's file and member, the severity the highest of them, and the
evidence the quoted lines. No text of a model is in it, and not which model judged: the issue, the fix and the
judge stay in `security-report.md` and `findings.jsonl`. The envelope is a full snapshot unless a file could not be read; such a run is `report.rejected`,
because nothing looked for findings in that file. `catalog.json` names the rules of the rule file and `other`, and marks every one as judged by a model
(`"judged": true`); a test keeps the rule file and the catalog in step.

**The areas are items, and a run judges only what needs a verdict.** Every area is an item of the audit
([docs/registers.md](../../../docs/registers.md), "Items"), named by its file and its first member
(`<file>#<member>`, with `~2` for a second area of the same member). `areas find` gives each a digest of what a
verdict about it sees: `--verdict-version`, the rules that picked the area, and its code without line numbers, so
an area that only moved keeps its digest. The judge of the run is not in it. The envelope lists every item, with `judged: true` for an area whose
answer the report step read, and every finding names the item of its area. The register keeps the digest and the
run of each item's last verdict and hands them to the next run as `{{ audit_items }}`:

| What the register says of the area | What the next run does |
|---|---|
| `clean` or `decided`, and the digest is the same | nothing: the area is not sent to the model |
| `not judged`, or unknown, or the digest differs: the code of the area changed | judges it |
| `open` and the digest is the same, or an open finding of the area is in `{{ audit_findings }}` | asks about its findings again, in the places the areas above leave (`--max-areas`) |

So the 110 calls of a first run become the handful of areas that changed since. `traceworks findings security`
shows how many items are settled, and `traceworks findings security items` lists them. Raise `--verdict-version`
in `workflow.json` when the prompt or the instructions change what counts as a finding, and every area is judged
again. Where nothing sets `{{ audit_items }}` (an older Traceworks), every area is judged. The subject
of a finding is still the area's file and all its members; an area whose members change keeps its item name as
long as the first one stays.

**One failed call is one area not judged.** A call that timed out, failed or answered without JSON, and an area
that got no place under the cap, leave their item with `judged: false`. So does an answer with a finding that did not
verify: the register never sees such a finding, and the item must not read clean because of it. The register applies the report, the item
keeps the verdict it had, and its open findings stay open. The gate stays YELLOW, and the next run judges the area.

**Low findings stay in the report of the run.** The instructions say what a severity means here: high when an
untrusted input breaks a guarantee of `docs/security.md` (it escapes the container or a mount check, reaches a
secret, runs code on the host, or changes the record unnoticed); medium when a guarantee holds only by luck, or
breaks under a condition the operator does not control; low for a hardening gap with no known way through. The
register and the gate take medium and high. A new finding the verdict rates low passes the same check and is listed
in `security-report.md` under "Low findings, in this report only", and nowhere else: not in the envelope, not in
`findings.jsonl`. Its area counts as judged, and can be clean. An open finding the register already holds is
answered as before, holds or gone, whatever its severity; and since the register knows a finding by its rule and its
area, a low finding of a rule that is open in the area states that finding again and is no new one.

**A finding is not resolved by silence.** A model that does not name a finding again has not said that it is
fixed, so the register keeps a finding of a judged rule open until a run says explicitly that it is gone
([docs/registers.md](../../../docs/registers.md), "Reconciliation"). The run therefore asks. The `areas` step gets
`{{ audit_findings }}`, and `find --known` sorts it to the areas: each line of `areas.jsonl` carries the open
findings of its own file and member, each with an id (`K1`), and the decided ones. The prompt holds only those,
between frame lines, and wants an answer per open finding in `"known"`:

| The answer | What the report step does | In the register |
|---|---|---|
| `{"id":"K1","status":"holds", severity, line, quote, issue, fix}` | checks the line and the quote like a new finding's, and states the finding again under its rule | seen, stays `OPEN` |
| `{"id":"K1","status":"gone","reason":"..."}` | names the finding in the envelope's `gone`; the reason goes to the report | `RESOLVED` |
| none, an unknown status, or `holds` with a quote that is not in the line | lists it under "Open findings without an answer"; the gate is at least YELLOW | stays `OPEN`, counted as not mentioned |

A finding whose file and member are no area any more (the member was removed or renamed) is named as gone by
`find` itself: it cannot hold as it was stated. Only `OPEN` and `AWAITING_VERIFICATION` findings are asked about.

```bash
traceworks findings security list
traceworks findings security dismiss <id> --note "RunPaths refuses this mount before the call"
```

A finding that was dismissed, or accepted as a risk, is decided: the prompt of its area shows it with the note, and
it is neither asked about nor reported again. A decision is a person's: check
a finding against the code before you accept or dismiss it. On 2026-11-11, before the verdicts could read beyond
their area, 8 of 13 findings checked by hand were wrong; on 2026-11-12, with `tools: read`, all 10 held.

## How the areas are found

The `areas` tool (`images/areas/src`, JDK only) reads every `src/main/java` up to six levels below the workspace
(not below `target`, `build`, `node_modules` or a hidden directory) in one `javac` task and attributes it, so a call
matches the type it resolves to: `new ProcessBuilder` and `Path#startsWith` are found whatever the variable is called,
across line breaks, through method references and for subtypes. The class path is empty: the sources resolve against
the platform and each other only. A call on a library type (Jackson) does not resolve; it is matched through the type
its receiver is written with (a creation, a cast, or a variable's declared type or its initializer's creation, read
with the imports of the file that declares it; the hit says `"resolved": "import"`); a receiver written with another
type does not match. Where no type is written, such as the result of a call or a method a source class inherits from a
library, the method name alone matches (`"resolved": "name"`, never for a `*` matcher). That can point at a call of
another type with the same name (on Traceworks: two `String#startsWith` taken for `Path#startsWith`); it costs one
area, and the AI judges it like any other. The
summary counts the references that did not resolve and names the rule types that are not in the sources or the
platform, in the summary and in the report. A file that does not parse, a second copy of a class, a symbolic link to a
file or a directory (also one that discovery could not enter), and a file that is no UTF-8 are named and keep the gate
from GREEN.

An area is the member (method, constructor, field, initializer) that holds hits, with every rule that hit there.
A member longer than 160 lines is cut into windows that reach 15 lines before and after its hits; hits whose windows
touch share one. Neighbouring areas of one file that fit 160 lines together become one area, so a class is judged in
few calls and with its context. At most 200 areas are judged; the rest are listed as cut. A hit's line is the line of
the method name, also at the end of a long fluent chain.

The same pass notes who calls whom: for every member, the members of the sources it calls, creates or refers to,
by the type the compiler resolved, and for a method every method of the sources that overrides it. No rule and no
step of this package reads that. The short check beside this audit,
[`security-changes`](../security-changes/README.md), takes its neighbours from it: it judges only the areas that
changed since a commit and the areas one call away, with `areas find --changes`, and keeps a copy of this tool, the
rule file and the catalog, which a test holds to the same bytes. Change the tool here and copy it there.

## The rule file

`rules/security.rules` names what the tool looks for. A rule points at areas; it decides nothing.

```
rule process_start
  why  Starts a process. Check that ...
  new  java.lang.ProcessBuilder
  call java.lang.Runtime#exec
  in   traceworks.adapters
```

| Line | Meaning |
|---|---|
| `rule <id>` | starts a rule; the id matches `[a-z][a-z0-9_]*` and is unique |
| `why <text>` | what the AI should check in an area of this rule; once per rule |
| `new <type>` | an instance creation of the type or a subtype, also `<type>::new` |
| `call <type>#<method>` | a call or method reference of the method on the type or a subtype; `*` for any method |
| `call ... nonconstant` | ... with at least one argument that is no compile-time constant; a method reference always counts, since its arguments come at run time |
| `call ... nonconstant <n>` | ... whose argument `n` (0 for the first) is no compile-time constant |
| `call ... constant <regex>` | ... whose first argument is a constant string in which the regex finds a match |
| `literal <regex>` | a string literal in which the regex finds a match |
| `in <package>` | the rule applies only in this package and below; several lines add up |

Blank lines and lines starting with `#` are ignored; an error is refused with its line number, and so is a `call` that
names a method its type (when the type is in the platform or the sources) does not have. The tool knows a constant
from literals, `static final` constants, string concatenation (with characters and numbers) and a conditional whose
condition is a constant; anything else counts as data.

## Limits

- The severity is the model's word. A model that rates everything low hides nothing from the report of the run, but
  keeps it out of the register and away from the gate. Read the low findings of a run when their share looks wrong.
- The tool points; it does not follow data. Whether a value in an area comes from an untrusted source is the AI's
  judgement, from the code it sees.
- An AI that misses a defect in an area misses it; the evidence check only removes findings that do not hold.
- Some rules name Traceworks' own types and packages (`redaction`, the `in` scopes). On another Java project, run it
  with `--workspace` and adapt the rule file.

## Tests

The tool's tests are in [`examples/self-audit/tests`](../tests), a module of the Maven build, so `mvn verify` runs
them; they make no AI call. One of them runs the package's shape through the real engine as a host run (it needs
`bash`), and the package's own workflow with a shell loop in the model's place: the verdict step is started with
the picked option, the report names it, and two picks feed one register. `ModelChoicePackageTest` runs the package
as it ships through `check`, `describe` and the start check of `run`, with a runtime that has no image. On its own,
run the module with `-am`: `mvn -pl examples/self-audit/tests -am test`. No test here runs the package in containers
with a model's CLI.
