# The run record

Every run leaves a folder. This page describes what is in it and how to check it without Traceworks.

The record has three parts:

- `events.jsonl`: the event log. One JSON object per line, appended in order, never changed.
- `blobs/`: the content the events refer to (outputs, commands, questions, answers).
- `manifest.json`: a summary, written when the run ends.

A copy of the manifest is also committed to the ledger (see "The ledger" below).

What is not in the event log did not happen.

## The run folder

Runs live in the Traceworks home, in `runs/<yyyy-mm-dd>/<run-id>/`. The date is the UTC date of the start.

```
runs/2026-11-03/c0c0f5eb-9c93-4d31-8cc5-b4172baedebd/
  events.jsonl     the event log
  blobs/           one read-only file per blob, named by its SHA-256
  manifest.json    written when the run ends (never for a parked run)
  run.html         a self-contained view of the run
  inbox/           requests from other processes, see docs/cli.md
  scratch/         the directory every step of the run shares; step.files records what it holds
  register/        only for an audit workflow: the files its register handed the run at its start, each for
                   reading only; run.started records their digests (docs/registers.md)
  run.lock         held by the one process that writes the run
```

Only the process that holds `run.lock` appends to `events.jsonl`. The operating system releases the lock when
that process dies, so a lock is never stale.

## Lines

A line is one JSON object followed by `\n`. These fields come first, in this order:

| Field | Meaning |
|---|---|
| `seq` | the number of the line, starting at 0 |
| `at` | the time, ISO-8601 in UTC with milliseconds: `2026-11-03T12:00:00.123Z` |
| `type` | the event type, such as `step.started` |
| `run` | the id of the run the event belongs to |
| `parent` | only in the events of a child run (a sub-workflow or a `foreach` item): the id of the parent run |
| `prev` | the chain hash, see below |

The fields of the type follow, in the order given in the sections below.

A child run (a `sub-workflow`, or a `foreach` item that runs a workflow) writes into the log of its parent. Its events
carry its own id in `run` and the parent's id in `parent`. It has no `run.started` and no `run.checkpoint`: its lines
run from the first event of its steps to its `run.finished` (a child refused before its first block has only that
one), and the parent's lines go on after it. The parent's step that ran it names its id (`subRunId` in the step's
metadata, `sub_run` in `foreach.item.finished`).

## The chain

Each line says which line came before it. That makes the log tamper-evident.

- `prev` of line 0 is the SHA-256 of the text `traceworks-run-v1|` followed by the run id, as lower-case hex.
- `prev` of line n is the SHA-256 of the bytes of line n-1, without its `\n`, as lower-case hex.
- The head is the hash of the last line.

If a byte of a line changes, a line is removed or lines are copied from another run, the next `prev` no longer
matches. `traceworks verify` finds it.

### Checking by hand

The genesis is the hash that line 0 must have as `prev`. Use the run id from the folder name:

```bash
printf 'traceworks-run-v1|%s' c0c0f5eb-9c93-4d31-8cc5-b4172baedebd | sha256sum
# fa1ec4bcd6d5f0787ae6cda4ffb6ae7844bb0112d827b97d48660bee4cb41e96
```

For line n, hash the line before it. `sed -n 1p` prints the first line, and `head -c -1` cuts off its `\n`:

```bash
sed -n 1p events.jsonl | head -c -1 | sha256sum     # must equal "prev" in the second line
```

To check every line, compare the hash of each line with the `prev` of the next:

```bash
prev=$(printf 'traceworks-run-v1|%s' "$RUN_ID" | sha256sum | cut -d' ' -f1)
while IFS= read -r line; do
  [ "$(printf '%s' "$line" | jq -r .prev)" = "$prev" ] || { echo "broken at: $line"; break; }
  prev=$(printf '%s' "$line" | sha256sum | cut -d' ' -f1)
done < events.jsonl
echo "head: $prev"
```

The head must equal `head` in `manifest.json`.

## Blobs

Values that come from run data are not written into the line. They are stored as blobs, and the line holds a
reference:

```json
{"sha256": "4ae7c3b6ac0beff671efa8cf57386151c06e58ca53a78d83f36107316cec125f", "size": 12}
```

The blob is the file `blobs/<sha256>`. Its name is the SHA-256 of its content, so you can check it:

```bash
sha256sum blobs/4ae7c3b6ac0beff671efa8cf57386151c06e58ca53a78d83f36107316cec125f
```

A blob holds a string as its UTF-8 text, bytes as they are, and anything else as JSON with sorted keys. A field
that has no value holds JSON `null` instead of a reference.

The rule is fixed: inputs, outputs, resolved commands, questions, answers, comments, errors, gate
descriptions, foreach items, step metadata, agentic progress data, checkpoint state and the workflow definition
are always blobs. Names, types, indexes, statuses, numbers, hashes and option lists are written inline.

A blob may be deleted later, for example to remove personal data. `traceworks verify` reports a deleted blob but
does not count it as a failure. A blob with other content than its name says is a failure.

A blob is on disk before a line names it: its content and its name are synced before the next line is appended, as
the line itself is synced. A blob file that a crash left with other content than its name says (empty, or cut) is
written again when the run stores the same content again.

What deleting a blob removes from the checkpoints depends on whether they refer to it. A checkpoint does not copy
texts: its state refers by blob to each step's input and output, the output of each named block, the previous output
and every variable that holds a text (see `run.checkpoint`). For the steps these are the blobs that `step.finished`
and `step.output` stored, and a variable that holds the same text as a blob of an event (an answer in
`approval.answered`, an item in `foreach.item.started`) refers to that blob, since equal texts are one blob. So once
such a blob is deleted, no checkpoint holds its text either. Everything else a checkpoint keeps, its state holds
inline: the run's inputs (`input_context`), the variables that hold no text, such as a number, a list or a map
(`context.variables`), `context.remembered_approvals`, and each step's `metadata` (a `failureSummary` there may quote
the step's output). Such a value is in its own blob (the inputs in `run.started`, say) and in the state of every
checkpoint written after it was set, so deleting its own blob leaves it there: to remove it from the record, delete
the state blobs of those checkpoints as well. The same holds for the texts of a checkpoint written by an earlier
version: a state without `format` holds the texts of the steps before it, and one of `format` 2 holds every variable
inline except the variables `step_<n>_output`, and in each step's `context_delta` the variables at that step's end.
Deleting a state disables resume: `traceworks resume` continues only from the latest checkpoint and refuses when its
state is gone.

A parked or interrupted run cannot go on without a text its latest checkpoint refers to: `traceworks resume`
refuses the run when one is deleted, as it does when the checkpoint's state is deleted (exit 6), and names the
text and its blob. `traceworks cancel` still ends such a run, as it does when the checkpoint's state itself is
deleted: without a gate verdict, which it would take from the steps' outputs. A blob of the checkpoint with other
content than its name says refuses both as damage (exit 8). A finished run needs no checkpoint.

`resume`, `approve`, `answer` and `cancel` act only on what the record says. Every blob they act on (the recorded
definition, the checkpoint state and every text it refers to, the parked question and its answer) is checked against
its SHA-256 and size, and a blob with other content refuses the command (exit 8). A run that reaches its end with a
chain that does not verify gets no manifest and no ledger entry.

## Redaction

The record holds no value of a secret. A run knows the value of every secret its definition declares (`secrets`,
see docs/cli.md) and of every credential it passes to an AI provider's container (docs/cli.md, "AI steps"); its
recorder replaces each of them, wherever it occurs, with `[redacted:<NAME>]` before anything is written. An AI answer
that quotes its key, a prompt or a tool result holding one are redacted like any output:

- **Lines:** every string of a line, nested ones and field names included, is redacted before the line is hashed.
  Numbers, booleans and blob references (`{"sha256": <64 hex digits>, "size": <n>}`), which must keep naming their
  blob, are kept. Any other field, also one called `sha256`, is redacted like the rest.
- **Blobs:** every blob is redacted before it is hashed and stored, so its reference names the bytes in
  `blobs/` and `traceworks verify` holds. That includes the outputs, the resolved commands, the errors, the step
  metadata, the run's inputs, the definition and the checkpoint state with the texts it refers to.
- **Scratch files:** a file in which a value occurs is listed in `step.files` with neither blob nor hash
  (`reason` `contains a secret`); its name is redacted like any string. Each new or changed file is scanned once.
  The file itself stays in `scratch/` as the step wrote it: the scratch directory is no part of the record
  (docs/security.md).
- **What is rendered from the record:** `run.html`, `manifest.json`, the ledger entry and everything `show`,
  `watch` and the run itself print come from the redacted record. The summary `run` and `resume` print last (the
  run's error and gate) is redacted the same way.

How a value is found:

- **Exact match.** A value is found byte for byte, as its UTF-8 bytes. A value holds no character JSON escapes, so
  it reads the same in a JSON blob such as a checkpoint, also where a step's JSON output sits inside it, as in plain
  text: Traceworks writes a character outside the BMP (such as an emoji) into the lines and JSON blobs as its UTF-8
  bytes, not as `\u` escapes. A record written before this version may hold such a character escaped; it is not
  rewritten. Any other encoding of a value (base64, a value split in two, a value another tool escaped or changed) is not
  found.
- **Overlaps.** Occurrences that overlap, of one value or of several, become one `[redacted:<NAME>]`, which names
  the longest of them; no part of any of them is left. Occurrences that only touch stay apart. Two names with the
  same value are replaced by the first name in sort order.
- **Cuts.** Wherever Traceworks shortens a text of the run (the run's error, the summaries of a `foreach`, a
  sub-workflow and an AI step, a compression, a SARIF fact sheet, an excerpt, the result of an `ai.tool_call`), a cut
  never falls inside a value: it moves to the value's edge, so the value is left out whole and the text gets no
  longer. A stream that did not end by itself broke off where nothing tells what followed: a stream cut at the output
  limit (docs/cli.md, "Output limit"), and each stream of a command that timed out, was cancelled or killed, or
  exited with any other code than 0. A shell step, an inner shell step, a tool step, a shell condition and an
  `items_command` keep such a stream up to before the first characters of any value it ends with, and an AI CLI's
  parser reads it only up to its last line feed. A command that exited 0 keeps its streams whole, and a stream that
  ends with a line feed loses nothing, since no value holds one. The record never holds a part of a value that a cut
  left over.
- **Refused values.** These values, of a secret or a credential, refuse the run at start (exit 6), naming it and the
  rule: shorter than 8 characters (it would be replaced where it is no secret); with `"`, `\`, a control character
  (below U+0020, or U+007F) or a lone surrogate (half of a character outside the BMP without the other half), which
  JSON escapes once more in every layer, so no exact match would find it there (U+007F is no such character, but a
  cut of text whose values are not known may start at it); with `'`, which a shell placeholder writes as `'\''`, a
  form with a backslash that JSON escapes once more in every layer, so no exact match would find it there either;
  one that the cleaning of untrusted text would change (a run of white space, U+0085, U+2028 or U+2029, or a marker
  such as `gate_status:` or `<open_todos>`), since the text of a tool or a model is cleaned before it is recorded and
  the record would then hold the value in a form no exact match finds (the fact sheet of a tool step replaces the
  values in the tool's text before it cleans that text, so the text around a value cannot change it there either);
  with whitespace at its start or end, which the shell blocks trim off every output, so the record would hold the
  value without it; only digits, or a value that reads as a JSON number (such as `-1234.5678`), which can stand in a
  number, where a replacement would break the record; containing `[redacted:`, which could not be told from a
  replacement.

**The engine keeps the real values.** A later step sees the real output of an earlier one, only the record does not.
A checkpoint is part of the record, so it holds the redacted values: a run that is resumed continues from them, and a
step after the resume sees `[redacted:<NAME>]` where the step before the park or the interruption saw the value.

**A resume redacts with the values held at the resume.** Its recorder reads the values again from
`credentials.json` and the environment as they are when it resumes. An old value of a secret that changed while the
run was parked or interrupted is not known to it: a later step that prints that old value (one an earlier step wrote
into a file, say) is recorded in clear. Nothing derived from a value is recorded to tell a change, since a hash of a
short value can be guessed. When `credentials.json` was modified after the run stopped and holds one of the run's
names, the resume prints a warning that names them (docs/cli.md, "resume"); the warning is not recorded, and it
cannot see a value from the environment. docs/cli.md ("resume") says how to avoid the leak.

## Event types

In the tables, a field marked "blob" holds a blob reference or `null`. All other fields are written inline.
Every example shows only the fields of the type, not the six common fields.

### run.started

The first line of a run's log, line 0. A child run has none: its lines stand in its parent's log (see "Lines").

| Field | Meaning |
|---|---|
| `workflow` | `{id, name}` of the workflow |
| `package` | `{dir, digest, git_commit, git_dirty}`: the package directory, its digest, and the git commit and dirty state of its directory (`null` when it is not in a repository) |
| `definition` | blob: the bytes of `workflow.json` as the run read them |
| `inputs` | blob: the inputs of the run, the `--input` values. The engine sets `package_dir`, which is no input; a record of an earlier version also has it here | The inputs of a child run are what its parent hands over, the parent's variables, and with them the reserved keys that carry which of them are protected: `__untrusted_keys` (the names of the variables from an untrusted source), `__untrusted_outputs` (the names of the block outputs of a tainted run, and `previous_output` for the last output), `__platform_in_shell` (`true` once platform data reached a shell line), `__platform_keys` (the names of the variables the parent built from platform data, a `foreach`'s `context` entry that names `audit_findings`, say: platform data in the child too), `__model_keys` alone when the parent holds a variable of model text without having asked a model itself (a run that gets `__model_keys` or `__model_outputs` counts as one that asked a model, whatever `__model_asked` says), and, once the parent asked a model, `__model_asked` (`true`), `__model_keys` (the names of the variables that may hold text a model wrote) and `__model_outputs` (the names of such block outputs, and `previous_output`). A key is there only when it has something to say
| `trigger` | `MANUAL` for a run started on the command line |
| `inputs_from` | only with `--inputs-from`: the id of the run whose recorded inputs this run took; the `inputs` blob holds the result, with the explicit `--input` values applied |
| `actor` | who started the run |
| `version` | the Traceworks version |
| `execution` | where the run runs its steps: `container` (wherever a container runtime works: every command in a container) or `host` (a host run, where none works and the operator consents: shell commands without an image on the host). A resume keeps it. A record of an earlier version says `host` |
| `container_runtime` | `{name, version}` of the container runtime the run uses (`docker` or `podman`, and the version it reported); `null` for a host run that found none |
| `output_limit_mib` | the output limit of every command of the run, in MiB: the operator's `output_limit_mib`, `16` by default (docs/cli.md, "Output limit"). A record of an earlier version has no such field; its limit was 16 MiB |
| `run_output_budget_mib` | the output budget of the run, in MiB: the operator's `run_output_budget_mib`, or its default (`128`, or eight times `output_limit_mib` when that is more), always as the number in force (docs/cli.md, "Output budget"). A record of an earlier version has no such field; its run had no budget |
| `ai_images` | the images the operator's `ai_images` runs AI CLIs in instead of their built-in images, as an object by CLI id (`{"claude": "registry.example.org/ops/claude:2.1"}`); `{}` when it replaces none (docs/cli.md, "AI images"). A record of an earlier version has no such field; it ran only the built-in images |
| `ai_network` | where the containers of the AI CLIs can connect: `restricted` (the default: only to the hosts of the call's provider and the operator's `ai_hosts`, through the egress proxy) or `open` (the runtime's default network), the operator's `ai_network` (docs/cli.md, "The network of an AI step"). A record of an earlier version has no such field; its AI containers had the open network |
| `ai_hosts` | the operator's `ai_hosts`: more hosts for the containers of an AI CLI, as an object of sorted lists by CLI id (`{"claude": ["gateway.example.org:8443"]}`); `{}` without any. A record of an earlier version has no such field |
| `images` | what each image the run may use holds, one object per image: `reference` (a built-in image by name, `shell`, `claude`, `pi`, `egress`; a package image as `./images/<name>`), `base` (the image of the Dockerfile's last `FROM`, without its digest) and `description` (the Dockerfile's label `org.opencontainers.image.description`, on one line, at most 200 characters; `null` without the label). The built-in images of this distribution and the package's own; a registry image a step names has no Dockerfile here and is not listed. A record of an earlier version has no such field |
| `seal` | the seal the package carried and the seal check accepted (docs/cli.md, "seal, unseal and seals"): `{workflow_id, digest, signer, sealed_at, key, principals, signature}`. `digest` is the seal digest, which covers the package and the packages it calls, so it differs from `package.digest`; `signer` and `sealed_at` are what the seal says; `key` is the fingerprint of the key that signed (`SHA256:...`); `principals` are the names the allowed signers file of the home gave that key when the run started; `signature` is the armored SSH signature. `null` for a package that is not sealed. A record of an earlier version has no such field |
| `model_choices` | only for a workflow that declares a model choice (docs/cli.md, "Model choices"): an object by the name of the choice, each `{option, default, provider, model}`: the option the run picked, whether it is the choice's default, and the provider and the model of that option, which the steps that refer to the choice ran with. The pick comes from the run's inputs (the `inputs` blob), and a resume keeps it. A run of a workflow without a choice has no such field, so its `run.started` is byte for byte what earlier versions wrote; a child run has no `run.started`, and its calls name their provider and model in `ai.request` |
| `register` | only for an audit workflow (docs/registers.md, "The register folder of a run"): what the register handed the run before its first step. `key` is the register; `seq` and `head` are the last line of the register's log the run read, its sequence number and its SHA-256, both `null` when the register did not exist yet; `files` lists the files of the run's `register/` folder in the order they were written, each `{name, sha256, size}`. The files are not blobs of the run: the folder is part of the run folder, `traceworks verify` compares it with these digests, a host run does so before each command that gets the folder, and a resume refuses a folder that differs. A run of any other workflow has no such field, so its `run.started` is byte for byte what earlier versions wrote |
| `workspace` | the directory the steps work in, as its real path (symbolic links resolved); with `--source`, the run's checkout `<home>/workspaces/<run-id>` |
| `workspace_source` | only with `--source`: the source the workspace is a checkout of, a local path as an absolute path, a URL without its credentials, query and fragment (`https://user:token@host/repo?x=1#y` is `https://host/repo`; an `ssh://` URL keeps its user name, which is no credential) |
| `workspace_source_sha` | only with `--source`: the full commit the checkout is detached at, which the steps also see as `workspace_source_sha` |

```json
{"workflow":{"id":"b1445bda-e182-479a-bf85-f5b519d68ad2","name":"Hello"},"package":{"dir":"/work/hello","digest":"ad91b638...","git_commit":null,"git_dirty":null},"definition":{"sha256":"3915...","size":912},"inputs":{"sha256":"ea6e...","size":2},"trigger":"MANUAL","actor":"anna@laptop","version":"0.1.0","execution":"container","container_runtime":{"name":"docker","version":"29.1.3"},"output_limit_mib":16,"run_output_budget_mib":128,"ai_images":{},"ai_network":"restricted","ai_hosts":{},"workspace":"/work/project"}
```

The `register` field of an audit run:

```json
"register":{"key":"hello","seq":17,"head":"6f486c7c...","files":[{"name":"findings.jsonl","sha256":"0c1e...","size":48211},{"name":"resolved.jsonl","sha256":"e3b0...","size":0},{"name":"items.jsonl","sha256":"e3b0...","size":0},{"name":"previous-report.md","sha256":"91ab...","size":7304}]}
```

### run.checkpoint

The state of the run, written before the first block and after every top-level step that lets the run go on.
`resume` continues from the latest one. Child runs write none.

| Field | Meaning |
|---|---|
| `next_position` | the position (index in the workflow's block list) of the block that runs next |
| `state` | blob: the full snapshot (variables, taint, block outputs, step counter, run metadata, remembered approvals, finished steps, inputs) |

```json
{"next_position":2,"state":{"sha256":"f4dd...","size":2843}}
```

The state is a JSON object. It does not hold the texts of the run; it refers to them by blob, so a checkpoint
grows with the number of steps and variables, not with the size of their texts:

| Field of the state | Meaning |
|---|---|
| `format` | `4`. `3` is the format before `context.taint.model_text`. Such a state, and one of an earlier format, does not say what a model wrote, so it is read as a run that asked a model, with every output and every variable that is not protected already as model text (docs/security.md, "Earlier checkpoints"); a version that knows only `3` refuses a state of format `4` |
| `context.variables` | the run's variables that hold no text: numbers, booleans, lists, maps and `null` |
| `context.text_variables` | the run's variables that hold a text, each as a blob reference: the inputs and run variables that are texts, the texts steps set (an answer, a `foreach` item) and `step_<n>_output` (the output of step n) |
| `context.block_outputs` | the output of each named block, each as a blob reference |
| `context.previous_output` | the output of the last block as a blob reference, or `null` |
| `context.taint`, `context.source_steps`, `context.next_step_index`, `context.run_metadata`, `context.remembered_approvals` | the rest of the run's context. `context.taint.model_text` is what may hold text a model wrote: `asked` (whether the run asked a model), the `variables` and `outputs` recorded since, and whether `previous_output` is one; a shell line that names one is refused |
| `steps` | the top-level steps so far; each one's `input` and `output` is a blob reference or `null`, its status, times and `metadata` are written inline |
| `input_context` | the variables the run started with, inline |

A field of this table that names a blob reference always holds one (`context.text_variables` never `null`), and
nothing else does: a value of the workflow's own, also one that looks like a reference, stays a value. A variable is
in `context.variables` or in `context.text_variables`, never in both. Each text is stored as `step.output` and
`step.finished` store theirs, redacted the same way, so the checkpoint names the same blob as the step's events and
adds no copy of it:

```json
{"format":4,"context":{"variables":{"retries":2},"text_variables":{"name":{"sha256":"bf4f...","size":4},"step_0_output":{"sha256":"4ae7...","size":12},"workflow_run_id":{...},...},"taint":{...},"block_outputs":{"greet":{"sha256":"4ae7...","size":12}},"previous_output":{"sha256":"4ae7...","size":12},"source_steps":{...},"next_step_index":1,"run_metadata":{},"remembered_approvals":{}},"steps":[{"id":"...","step_index":0,"block_name":"greet","block_type":"shell","status":"COMPLETED","input":{"sha256":"9e03...","size":19},"output":{"sha256":"4ae7...","size":12},"started_at":"...","completed_at":"...","duration_ms":8,"metadata":{...}}],"input_context":{"name":"Anna","retries":2}}
```

`resume` reads every text the state refers to and checks it like the state itself; `cancel` checks them too, but a
deleted one only drops the gate (see "Blobs"). `verify` checks those blobs as it checks the blobs of the lines.
Traceworks still reads the states earlier versions wrote, so a run parked before still resumes and cancels: a state
without `format` holds the texts itself, and one of `format` 2 refers to the texts of the steps as this one does but
holds every variable inline except `context.step_output_variables` (the variables `step_<n>_output` that hold a
text, each as a blob reference), and gives each step a `context_delta` with the variables at its end, which is not
read.

### step.started

| Field | Meaning |
|---|---|
| `step` | the step index of the run |
| `name` | the block name |
| `block_type` | the block type, such as `shell` |
| `position` | the index of the block in its list |
| `mode` | `deterministic` or `ai`: whether the block as written can ask a model. A `switch` is `ai` when any of its case or default blocks is; a `foreach` with `workflow_id` and no `execution_mode` is `deterministic`; an `approval` or `human-input` whose `actor` is still a placeholder counts as `ai` |
| `uses` | the sorted names of the placeholders in the block's config; `[]` for a child run's step |

```json
{"step":0,"name":"greet","block_type":"shell","position":0,"mode":"deterministic","uses":["name"]}
```

### step.waiting

The step starts or stops waiting for a person.

| Field | Meaning |
|---|---|
| `step` | the step index |
| `waiting` | `true` when it waits, `false` when it goes on |

```json
{"step":2,"waiting":true}
```

### step.command

A command is about to run. It is written by the command runner, so it also appears for the commands of nested
blocks.

The fields are the facts the run's command runner resolved before the command starts: the image ID it starts from,
every mount it gets and the directory it starts in. In a container run, a command without an image runs in the
built-in `shell` image, and the standard mounts come first: the workspace at `/workspace` (read-only unless the block
sets `workspace_writable: true`), the package at `/package` (read-only) and the run's scratch directory at `/scratch`
(writable); in the run of an audit workflow the run's register folder follows at `/register` (read-only), for every
command but an AI call (docs/registers.md, "The register folder of a run"); when the workspace is writable and holds
the package, the package follows once more, read-only, at its place in `/workspace`. In a host run, a command without an image runs on the host, and a container step sees each of
these directories at its host path. A command that cannot run as planned is still recorded, with `image_id` `null`;
its `step.command.finished` has exit code 125, and nothing was started. That is a command whose image is not present,
an image reference that is blank or starts with `-`, a `./images/<name>` whose name is not valid, a declared mount on
or below `/workspace`, `/package` or `/scratch`, and in the run of an audit workflow `/register` (in a host run: on
or below their host paths), a command
of a host run whose register folder is no longer the one `run.started` recorded, a writable workspace
that is the package or lies inside it, a command that needs a container in a host run without a container runtime,
and a container that the runtime refuses before it starts it: a declared mount that the mount check refuses (such as
one of `~/.ssh`; docs/security.md, "Mounts"), a mount path that the runtime's command line cannot carry (with a comma,
a quote or a line break), and arguments or an environment with a character beyond ASCII under a locale that is not
UTF-8 (docs/cli.md, "Locale"). The runtime checks a declared mount once more right before the container starts; a
command whose mount is refused only then, because its path changed in between, ends the same way, but with its
`image_id` recorded.

| Field | Meaning |
|---|---|
| `step` | the step index (`null` outside a step) |
| `item` | the `foreach` item index, or `null` |
| `command` | blob: the resolved command text, as the workflow wrote it with its trusted placeholders filled in; for a container's own entrypoint, a JSON array of its arguments |
| `values` | only for a command of a block with `values`: a blob that holds a JSON object from each variable name to the value the command found in it (docs/cli.md, "Values: how a protected value reaches a command"). The values are no part of `command`; what bash ran is the lines that set them, then `command`. A secret in a value is redacted like any text |
| `working_dir` | the directory the command runs in, inside the container for a container command |
| `image` | the container image as the step names it (`./images/<name>` for a package image, `shell` for the default image), or `null` on the host |
| `image_id` | the local ID of the image the container starts from (`sha256:...`), or `null` on the host and for a command that starts no container (see above) |
| `mounts` | list of `{host, container, read_only}`: the standard mounts first, then the block's own |
| `network` | where the container can connect: `none` (no network), `open` (the runtime's default network: a shell step or tool with `network: true`, and an AI call when the operator's `ai_network` is `open`) or `hosts` (only the hosts in `network_hosts`: an AI call). A record of an earlier version says `false` or `true` here, which is `none` or `open` |
| `network_hosts` | only with `network: "hosts"`: the targets the container can reach, each `host:port` in lower case, sorted. They are the hosts of the call's provider, the operator's `ai_hosts` for its CLI and, for a step with `web: fetch`, its `web_hosts`; `*` among them (first) is the star of `web_hosts`, for which the proxy allows only public names on port 443; the container has no network but the egress proxy, which connects to these and to nothing else (docs/security.md, "AI steps") |
| `env_names` | the sorted names of the environment variables the step set, such as the secrets it declares (never their values) |
| `host` | `true` when the command runs on the host |

```json
{"step":0,"item":null,"command":{"sha256":"9e03...","size":19},"working_dir":"/workspace","image":"shell","image_id":"sha256:5f2c...","mounts":[{"host":"/work/project","container":"/workspace","read_only":true},{"host":"/work/hello","container":"/package","read_only":true},{"host":"/home/user/.traceworks/runs/2026-11-04/c0c0f5eb.../scratch","container":"/scratch","read_only":false}],"network":"none","env_names":[],"host":false}
```

An AI call (its mounts left out here):

```json
{"step":1,"item":null,"command":{"sha256":"41aa...","size":212},"working_dir":"/workspace","image":"traceworks/claude:b3bb83ad9445","image_id":"sha256:aae8...","mounts":[],"network":"hosts","network_hosts":["api.anthropic.com:443"],"env_names":["ANTHROPIC_API_KEY","DISABLE_AUTOUPDATER"],"host":false}
```

On the host:

```json
{"step":0,"item":null,"command":{"sha256":"9e03...","size":19},"working_dir":"/work/project","image":null,"image_id":null,"mounts":[],"network":"none","env_names":[],"host":true}
```

### step.command.finished

| Field | Meaning |
|---|---|
| `step` | the step index |
| `item` | the `foreach` item index, or `null` |
| `exit_code` | the exit code of the command; `153` for one that Traceworks stopped because a stream went beyond the output limit, unless its time limit or a cancel ended it first |
| `duration_ms` | how long it ran |
| `timed_out` | whether it was stopped by its time limit |
| `truncated` | the streams that went beyond the output limit, `stdout` before `stderr`; `[]` when the output was whole. The limit is the `output_limit_mib` of `run.started`, or of the last `run.resumed` before the command (16 MiB by default). Of such a stream only its first part up to the limit was kept, and what ran the command failed (docs/cli.md, "Output limit"). On the host, `stdout` is the merged output. A record of an earlier version has no such field |
| `egress` | only for a command with `network: "hosts"`: what its container asked the egress proxy for, as a list of `{target, verdict, count}`, counted by target and verdict, in the order of their first appearance; `[]` when it asked for nothing. `target` is `host:port` as the container wrote it, in lower case, or `?` for a request that named none; it comes from inside the container. `verdict` is `ALLOW` (connected), `DENY` (not in `network_hosts`, or a plain HTTP request, which the proxy never forwards, or, for a target only `*` allows, another port than 443, an IP address or a name that resolves to an address that is not public), `FAIL` (listed, but the connection failed) or `LIMIT`. Nothing else of a connection is recorded: no path, no header, no content. A `DENY` fails no step and changes no gate; `show` and `run.html` mark it and count it. The list names at most 100 targets; the requests to further ones are counted together under the target `(other targets)`. After 10,000 requests the proxy refuses every further one, and the list ends with `{"target": "*", "verdict": "LIMIT", "count": 1}`. A command that could not start its proxy did not run (exit code 125) and has `[]` |

```json
{"step":0,"item":null,"exit_code":0,"duration_ms":8,"timed_out":false,"truncated":[]}
```

An AI call whose model, with shell tools, also tried another host:

```json
{"step":1,"item":null,"exit_code":0,"duration_ms":18244,"timed_out":false,"truncated":[],"egress":[{"target":"api.anthropic.com:443","verdict":"ALLOW","count":3},{"target":"paste.example.org:443","verdict":"DENY","count":1}]}
```

A command whose output went beyond the limit:

```json
{"step":0,"item":null,"exit_code":153,"duration_ms":412,"timed_out":false,"truncated":["stdout"]}
```

### ai.request

An AI step is about to call its model. Every AI call of a run is one container command: the provider's CLI runs in
its built-in image (`traceworks/claude:<h>` or `traceworks/pi:<h>`, or the operator's `ai_images` replacement) with the
prompt on stdin, in a host run too, and it can connect only to the hosts of its provider (`network: "hosts"`), unless
the operator's `ai_network` is `open`. So each call is written as `ai.request`, then the CLI's `step.command` and
`step.command.finished` (`image` is the tag; `env_names` names the provider's credentials and the CLI's own
variables, such as `TRACEWORKS_SYSTEM_PROMPT`, which carries the system prompt, never their values), then one
`ai.tool_call` per tool call, in order, and last `ai.response`. The tool calls are written once the call has
returned. All four are attributed to the step and `foreach` item as a command is; the attribution of the request
holds for all of them.

| Field | Meaning |
|---|---|
| `step` | the step index |
| `item` | the `foreach` item index, or `null` |
| `provider` | the provider id, such as `CLAUDE` |
| `model` | the model asked for, or `null` for the CLI's default |
| `effort` | the reasoning effort asked for, or `null` |
| `tools` | the tool policy of the call: `none`, `read`, `write` or `shell` |
| `web` | whether the model may use the web: `none`, `search` (the provider's search tool) or `fetch` (search, and the fetch of a page from the call's container); docs/cli.md, "Web tools of an AI step". A record of an earlier version has no such field; its calls had no web tools |
| `web_hosts` | only with `web: "fetch"`: the hosts the step named, each `host:port` in lower case, or `["*"]` for any public host. They are among the `network_hosts` of the call's `step.command`, unless the operator's `ai_network` is `open`: then they are recorded here and nothing enforces them |
| `prompt` | blob: the request's prompt; the system prompt has a field of its own, and the CLI's arguments are in the `step.command` that follows |
| `system_prompt` | blob: the system prompt, or `null` |

```json
{"step":1,"item":null,"provider":"CLAUDE","model":"opus","effort":null,"tools":"read","prompt":{"sha256":"77b0...","size":412},"system_prompt":null}
```

### ai.tool_call

One tool call the model made during the call, as its CLI reported it.

| Field | Meaning |
|---|---|
| `step` | the step index |
| `item` | the `foreach` item index, or `null` |
| `tool` | the tool's name as the CLI reports it, such as `Read` or `bash`, cut at 256 characters (never inside a secret value) |
| `arguments` | blob: the arguments the model passed, as the CLI's JSON text |
| `result` | blob: what the tool returned, cut at 2 000 characters; the cut never splits a secret value or a character |
| `error` | whether the tool reported an error |

```json
{"step":1,"item":null,"tool":"Read","arguments":{"sha256":"0c1d...","size":31},"result":{"sha256":"e3b0...","size":2000},"error":false}
```

### ai.response

How the call ended. Every usage figure is `null` when the CLI reported none.

| Field | Meaning |
|---|---|
| `step` | the step index |
| `item` | the `foreach` item index, or `null` |
| `model` | the model that answered, as the CLI reported it (Pi's with its backend, such as `anthropic/claude-sonnet-4-5`), cut at 256 characters (never inside a secret value) |
| `input_tokens`, `output_tokens` | the tokens the call used, as the CLI counted them, summed over its turns. `input_tokens` is only the input the prompt cache did not serve: with Claude, whose CLI caches the prompt, it is often a handful of tokens for a prompt of many thousand |
| `cache_read_input_tokens`, `cache_creation_input_tokens` | the rest of the input: the tokens the provider read from its prompt cache, and the tokens it wrote to it. `null` when the CLI reported none; a record of an earlier version has no such fields. What the call sent is the sum of `input_tokens` and these two, and that sum is the `in` figure of `show`, `run.html` and `diff`. Claude's result line counts the three apart, and so does Pi's stream against Anthropic (`input`, `cacheRead` and `cacheWrite`, whose sum with the output is its `totalTokens`; recorded from Pi 1.5.2 against a fake Anthropic API), so the sum counts nothing twice there. For Pi's other backends this was not verified: the sum is then what Pi reported in its three fields. The token budget of an `agentic` block (`max_total_tokens`) is another count, on purpose: it adds up `input_tokens` and `output_tokens` only, without the cached part, which it was sized for, so a block can show more tokens in than its budget allows and still be within it |
| `cost_usd` | what the call cost, as the CLI reported it |
| `num_turns` | how many turns the CLI took |
| `exit_code` | the CLI's exit code; non-zero for a failed call (also one the CLI ended with 0 but reported as an error, then `1`), `124` for one that ran out of time and `153` for one whose stream went beyond the output limit, whose `error` then says so whatever the CLI's cut stream held. A failed call is recorded whole, a reasoning call too: its tool calls, its exit code and its usage. `null` only when the call could not report an outcome (it threw before its CLI's output was read) |
| `output` | blob: the answer, for exit code 0; else `null` |
| `error` | blob: why the call failed: the CLI's message, or what the call threw; `null` when it answered |

```json
{"step":1,"item":null,"model":"claude-opus-4-6","input_tokens":4,"output_tokens":212,"cache_read_input_tokens":1620,"cache_creation_input_tokens":216,"cost_usd":0.031,"num_turns":2,"exit_code":0,"output":{"sha256":"5d41...","size":118},"error":null}
```

A call that the process did not live to end has its `ai.request` and maybe its command, but no `ai.response`.

### step.output

| Field | Meaning |
|---|---|
| `step` | the step index |
| `name` | the block name |
| `output` | blob: the step's output |

```json
{"step":0,"name":"greet","output":{"sha256":"4ae7...","size":12}}
```

### gate.reported

A step reported a gate verdict: a line of its output that starts with `gate_status:` (after a line feed; `gate_category:`
and `gate_description:` lines add to it). Steps whose output is no gate source report none: a compression or a
sub-workflow hand-up, which copies another step's text; a wrapper in AI mode (`foreach`, `retry`, `while`,
`conditional`), whose output a model wrote; and a step whose output holds a command output that the output limit cut
(docs/cli.md, "Output limit"), whose markers are those printed before the cut: a shell step, a tool step, and a
wrapper whose inner shell step was cut; and a `foreach`, `while` or `switch` that stopped at the run's output budget
(docs/cli.md, "Output budget"), whose output lacks the item that did not fit; a step the budget failed carries
`outputBudgetExceeded: true` as well. The last three kinds carry `gateMarkers: false` in their metadata. A `checked_command` step that was cut still reports the gate of its exit code
(RED).

| Field | Meaning |
|---|---|
| `step` | the step index |
| `status` | `GREEN`, `YELLOW` or `RED` |
| `category` | the gate category, or `null` |
| `description` | blob: the gate description |

```json
{"step":1,"status":"GREEN","category":"hello","description":{"sha256":"f59b...","size":17}}
```

### approval.requested

An `approval` or `human-input` step asks a question.

| Field | Meaning |
|---|---|
| `step` | the step index |
| `kind` | `approval` or `human-input` |
| `block_id` | the id of the block |
| `question` | blob: the question text |
| `options` | the offered answers (approval) |
| `reject_options` | the options that count as a rejection |
| `input_type` | the input type (human input), or `null` |
| `default` | blob: the default value, or `null` |
| `required` | whether an answer is required |
| `pattern` | the validation pattern, or `null` |
| `variable` | the variable that receives the answer, or `null` |
| `fields` | the fields of a form, or `null` |
| `parks` | whether this question parks the run (see docs/cli.md) |
| `preset` | whether an answer given earlier answers it at once |

```json
{"step":2,"kind":"approval","block_id":"approval-2","question":{"sha256":"ff07...","size":36},"options":["Yes","No"],"reject_options":["No"],"input_type":null,"default":null,"required":false,"pattern":null,"variable":null,"fields":null,"parks":true,"preset":false}
```

### approval.answered

A question was answered.

| Field | Meaning |
|---|---|
| `step` | the step index |
| `kind` | `approval` or `human-input` |
| `answer` | blob: the answer |
| `actor` | who answered, or `null` when nobody did: the step took the answer by itself, such as a question that timed out or took its default. An answer that arrives as the question's wait ends either resolves it and is named here, or is refused (one from the inbox with `request.refused`) and is not |
| `source` | where the answer came from: `terminal`, `inbox` (`traceworks approve` or `answer` sent it to a running run), `cli` (`traceworks approve` or `answer` on a parked run) or `resume` (a resumed run received an answer recorded earlier) |
| `request` | the id of the request that carried the answer, or `null` |
| `comment` | blob: the comment given with the answer, or `null` |

```json
{"step":2,"kind":"approval","answer":{"sha256":"85a3...","size":3},"actor":"anna@laptop","source":"cli","request":"6efd92f3-5b4e-4d81-a5da-62e8db978c3a","comment":null}
```

After a park, the log holds the answer twice: once when it was given (`cli`) and once when the resumed step
received it (`resume`).

### request.refused

A request in the inbox was not carried out.

| Field | Meaning |
|---|---|
| `kind` | the kind of the request: `approval`, `human-input` or `cancel` |
| `request` | the request id |
| `actor` | who sent it |
| `reason` | why it was refused, for example that another answer came first |

```json
{"kind":"approval","request":"6efd92f3-5b4e-4d81-a5da-62e8db978c3a","actor":"anna@laptop","reason":"the run was not running when the request arrived"}
```

An answer left in the inbox while no process ran the run is refused with the reason `the run was not running when
the request arrived` by the `resume` or `cancel` that takes the run over: it was bound to a question of a run that went
on without it. A cancel is never refused that way. It is meant for whichever process runs the run next, which
records it as `run.cancel_requested` and ends the run `CANCELLED`.

### foreach.item.started

| Field | Meaning |
|---|---|
| `step` | the step index |
| `item_index` | the index of the item |
| `total` | the number of items |
| `item` | blob: the item |

```json
{"step":4,"item_index":0,"total":3,"item":{"sha256":"c1d2...","size":5}}
```

### foreach.item.finished

| Field | Meaning |
|---|---|
| `step` | the step index |
| `item_index` | the index of the item |
| `total` | the number of items |
| `status` | the item's status: `COMPLETED`, `FAILED` or `CANCELLED` (the run's cancel ended the item; it is no failed item) |
| `output` | blob: the item's output |
| `sub_run` | the id of the child run, when the item ran a workflow; else `null` |
| `resumed_from` | only for an item a resumed loop took over instead of running it: the `seq` of the run's earlier `foreach.item.finished` that holds the item |
| `item` | only with `resumed_from`: blob, the item. No `foreach.item.started` comes before such a line, so it names the item itself |

```json
{"step":4,"item_index":0,"total":3,"status":"COMPLETED","output":{"sha256":"9a8b...","size":7},"sub_run":null}
```

**A resumed loop.** When a run is resumed and the step that runs again (`rerun_step` in `run.resumed`) is a
`foreach`, the loop takes over the items its last pass in the record finished, and runs the rest (docs/cli.md,
"resume", says which items count). A taken-over item is recorded again, with its output, so the lines of the resumed
pass hold every item of the loop and say where each output comes from:

```json
{"step":4,"item_index":0,"total":3,"status":"COMPLETED","output":{"sha256":"9a8b...","size":7},"sub_run":null,"resumed_from":21,"item":{"sha256":"c1d2...","size":5}}
```

The commands, AI calls and token counts of a taken-over item are in the lines of the pass that ran it, before
`resumed_from`. The step's metadata (`step.finished`) counts the taken-over items in `itemsResumed` and marks each in
`items` with `resumed: true`.

### agentic.progress

Progress of an `agentic` step.

| Field | Meaning |
|---|---|
| `step` | the step index |
| `phase` | the phase of the step |
| `data` | blob: the progress data |

```json
{"step":5,"phase":"plan","data":{"sha256":"77aa...","size":230}}
```

### step.files

Written right after `step.finished` when the scratch directory differs from the listing the record holds. A run
with a scratch directory that no step touched has no `step.files`. The directory is listed without following links;
special files such as pipes are skipped. The events of child runs list the same directory, the one of the root run.

| Field | Meaning |
|---|---|
| `step` | the step index |
| `files` | every regular file, sorted by `path`, as `{path, size, sha256, blob, reason}`. `blob` is the content as a blob reference, stored when the file is new or changed; `blob` is `null` and `reason` says why when the file is over 16 MiB (`too large`) or holds a secret (`contains a secret`; then `sha256` is `null` too, since the hash of a file that holds a short secret could be guessed). A file over 1 GiB is not read: its `sha256` is `null` and `reason` is `too large to hash`. A file whose size and modification time did not change keeps its earlier hash and blob without being read |
| `links` | every symbolic link as `{path, target}`. The target is the link's text; it is never followed or read |
| `unreadable` | every entry that could not be read, as `{path, reason}`: a directory or file without permission (`permission denied`), a path that is too long (`path too long`), any other I/O error (`I/O error`), or a file that changed so that it could not be read (`unreadable when recorded`). Nothing is left out silently, and an unreadable path is not reported under `removed`. The path `""` is the whole directory: when a container of the run may still be running (its removal after a timeout or a cancel was not confirmed), the directory is not listed at all, so no listing races a container that still writes; the reason is then `not listed: a container of the run may still be running`, `files` is empty, and the next listing shows the directory as it is |
| `removed` | the paths that were in the last listing and are gone. A listing that was cut reports none |
| `truncated` | `true` when the listing was cut. The walk of the directory holds at most 10 000 paths at a time: the files and links it has listed plus the files, links, folders and special files it has seen and not yet visited. Of a folder with more children than there is room for, the first by name are walked and the rest is left out, whatever the shape of the tree (one wide folder, or folders nested in folders). So a listing never has more than 10 000 entries, a directory with at most 10 000 files, links, folders and special files in all is always listed whole, and a larger one can be cut before it has 10 000 entries. Directories are walked in sorted order, so the cut is the same every time |

A name is the text of the path with `/` as separator. A name that is not valid text shows the replacement character
for the bytes that are not; it is still listed and read.

The `sha256` and `size` of a file with a blob are those of the stored content.

```json
{"step":0,"files":[{"path":"report.txt","size":5,"sha256":"2cf2...","blob":{"sha256":"2cf2...","size":5},"reason":null}],"links":[],"unreadable":[],"removed":[],"truncated":false}
```

### run.status

| Field | Meaning |
|---|---|
| `status` | the new status of the run |

```json
{"status":"RUNNING"}
```

### step.finished

| Field | Meaning |
|---|---|
| `step` | the step index |
| `name` | the block name |
| `block_type` | the block type |
| `status` | the step's status: `COMPLETED`, `FAILED`, `SKIPPED` or `CANCELLED` |
| `duration_ms` | how long the step took |
| `input` | blob: the resolved input of the step |
| `metadata` | blob: the step's metadata |

```json
{"step":0,"name":"greet","block_type":"shell","status":"COMPLETED","duration_ms":8,"input":{"sha256":"9e03...","size":19},"metadata":{"sha256":"5c0e...","size":120}}
```

A step whose block stopped the run instead of returning a result (a setting of the wrong type, a refused mount) is
finished too, `FAILED`, with the error as its output (`step.output`), before `run.finished`; only a failed write of the
record leaves a step without its end.

`CANCELLED` is a step the run's cancel (`traceworks cancel`, a second Ctrl-C) ended while it ran or waited: a command the cancel
killed, an AI call it aborted, a wait or a question it ended, a loop it cut short. Its output is what the step had when
it ended, or `Cancelled: the run was cancelled while this step ran` for a block that stopped the run instead of
returning a result. When what stopped it was a JVM error (`OutOfMemoryError`, `StackOverflowError`, any
`java.lang.Error`), which a cancel does not cause, a second line names it: `Ended by <class name>: <message>`, such as
`Ended by java.lang.OutOfMemoryError: Java heap space`, or only the class name when the error has no message; no stack
trace. An ordinary exception (a killed stream, an interruption) adds no line, since the cancel usually caused it. The
line is output like any other, so the record redacts the secret values in it. A cancelled step is finished, but it is no failure and no result: it sets no gate (no
`gate.reported`), counts as no failed item or attempt, and the run ends `CANCELLED` after it, without an error, also
when its block stopped the run with an exception. Only a failed write of the record ends such a run `FAILED`. A step that completed
or was skipped before the cancel took effect keeps its status, and a timeout or an output-limit kill without a cancel
stays `FAILED`. Records of earlier versions hold no `CANCELLED` step: there such a step is `FAILED`. A reader of
these records should accept a status it does not know; Traceworks before this version refuses to read a record that
holds one.

A step that was still open when the run ended `CANCELLED` (a parked run that `traceworks cancel` ended, or an
interrupted one) has no `step.finished`: it never finished, and the cancel appends no event for it. `show`,
`run.html` and the run view show it `CANCELLED`, derived from `run.finished`.

### run.parked

The run stopped at a point from which another process can go on, and the process exits. There are two reasons,
named by `kind`:

- a question nobody could answer in the process (`approval`, `human-input`). The next events come from `traceworks
  approve` or `traceworks answer` (the answer, `approval.answered` with source `cli`), then `traceworks resume`;
- a pause (`pause`): `traceworks pause` or the first Ctrl-C asked the run to stop (`run.pause_requested` comes before), and
  it finished what was in flight first. It waits for no answer.

Either way the run goes on with `traceworks resume` (from `run.resumed` on) or ends with `traceworks cancel`
(`run.cancel_requested`, then `run.finished`); nothing else writes to a parked run.

| Field | Meaning |
|---|---|
| `step` | the step that is not finished: the one that waits for an answer, or the `foreach` a pause stopped between two items, which a resume runs again with the items it finished. `null` for a pause between two steps, where every step that started is finished |
| `kind` | `approval`, `human-input` or `pause` |

```json
{"step":2,"kind":"approval"}
{"step":null,"kind":"pause"}
```

### run.pause_requested

Someone asked the running run to pause: `traceworks pause` through the inbox, or the first Ctrl-C in the run's
terminal. The run finishes the step or the loop item in flight and parks (`run.parked` with the kind `pause`); when
that step was the last one, the run ends instead. A run records it once: a second request is refused
(`request.refused`).

| Field | Meaning |
|---|---|
| `actor` | who asked for the pause |
| `request` | the request id |

```json
{"actor":"anna@laptop","request":"6efd92f3-5b4e-4d81-a5da-62e8db978c3a"}
```

### run.resumed

`traceworks resume` went on from a checkpoint.

| Field | Meaning |
|---|---|
| `actor` | who resumed the run |
| `from_checkpoint` | the `seq` of the checkpoint it restored |
| `next_position` | the position it continues at |
| `rerun_step` | the step that runs again: the one at `next_position` when it had started after the checkpoint (it was in flight when the run was interrupted, finished without a newer checkpoint, or asked the question the run parked at), or `null` |
| `answer` | the `seq` of the `approval.answered` line that answers the parked question, or `null`. A resume that ended before the run reached a newer checkpoint (its process was killed) leaves the answer to the next resume, which records the same `seq` and does not ask again |
| `package_changed` | whether the package digest differs from the start (`true` only with `--allow-changed-package`) |
| `package_digest` | the digest of the package now |
| `execution` | the target the run keeps, as `run.started` records it: `container` or `host`. A host run resumes on the host also where a container runtime works now |
| `container_runtime` | `{name, version}` of the container runtime of the process that resumed the run, which may differ from the one in `run.started` (another machine, an update); `null` when it found none (a host run) |
| `output_limit_mib` | the output limit of the commands after the resume, in MiB: the operator's setting when the run resumed, which may differ from the one in `run.started`. A record of an earlier version has no such field |
| `run_output_budget_mib` | the output budget of the run after the resume, in MiB, as in `run.started`: the one in force when the run resumed, which may differ from the one there. The resumed run counts the texts of its checkpoint and the variables its steps set against it; a resume whose checkpoint already holds more is refused, and nothing is recorded. A record of an earlier version has no such field |
| `ai_images` | the operator's `ai_images` when the run resumed, as in `run.started`, which may differ from the one there: the AI calls after the resume run in these images. A record of an earlier version has no such field |
| `ai_network`, `ai_hosts` | the operator's `ai_network` and `ai_hosts` when the run resumed, as in `run.started`, which may differ from the ones there: the AI calls after the resume connect as these say. A record of an earlier version has no such fields |
| `model_choices` | only for a workflow that declares a model choice: the picks the resumed run goes on with, as in `run.started`: per choice `{option, default, provider, model}`. The option is the one `run.started` recorded; `default` says whether it is the default of the definition the resume runs. A run of a workflow without a choice has no such field |
| `workspace_cloned` | only for a run started with `--source`: `true` when its checkout was missing and the resume cloned the source again at `workspace_source_sha`, so whatever a step had written there before is gone; `false` when it works in the checkout as it was |

```json
{"actor":"anna@laptop","from_checkpoint":14,"next_position":2,"rerun_step":2,"answer":19,"package_changed":false,"package_digest":"ad91b638...","execution":"container","container_runtime":{"name":"docker","version":"29.1.3"},"output_limit_mib":16,"run_output_budget_mib":128,"ai_images":{},"ai_network":"restricted","ai_hosts":{}}
```

A resume runs the package's `workflow.json`, which is the definition the run started with as long as the package
digest is unchanged. The definition in `run.started` is redacted like every blob and is never executed; only a resume
with `--allow-changed-package` runs it, and refuses when it holds a `[redacted:<NAME>]` of a secret the run uses
(docs/cli.md, "resume"). It keeps the run's
`execution`, and a run started with `--source` keeps its checkout (see `workspace_cloned`). Before it records
anything, it removes what is left of the run's containers and is refused unless no container with the run's label is
left.

### run.cancel_requested

| Field | Meaning |
|---|---|
| `actor` | who asked for the cancel |
| `request` | the request id, or `null` |

```json
{"actor":"anna@laptop","request":"3b1c9a52-0d3e-4a8e-b7f4-5d9a1e0f6c21"}
```

### run.recovered

A process was killed while it wrote. The next writer found a last line without its `\n`, cut it off and
recorded it.

| Field | Meaning |
|---|---|
| `discarded` | blob: the bytes that were cut off |

```json
{"discarded":{"sha256":"e3b0...","size":84}}
```

### run.finished

The last line of a run that ended. After it, nothing is appended. A child run's `run.finished` ends only its own
lines: its parent's lines follow it in the same log.

| Field | Meaning |
|---|---|
| `status` | `COMPLETED`, `COMPLETED_WITH_ERRORS`, `FAILED` or `CANCELLED` |
| `gate` | `{status, category}` of the run's verdict, or `null` when no step reported one |
| `gate_description` | blob, or `null` |
| `gate_summary` | blob, or `null` |
| `error` | blob: the error message, or `null` |

```json
{"status":"COMPLETED","gate":{"status":"GREEN","category":"hello"},"gate_description":{"sha256":"f59b...","size":17},"gate_summary":{"sha256":"810b...","size":147},"error":null}
```

## The manifest

`manifest.json` is written when a run reaches a final status (`COMPLETED`, `COMPLETED_WITH_ERRORS`, `FAILED` or
`CANCELLED`). A cancelled parked run gets one. A parked run does not.

| Field | Meaning |
|---|---|
| `run_id` | the run id |
| `workflow` | `{id, name}` |
| `package_digest` | the digest of the package |
| `seal` | `unsealed`, or `sealed` for a run of a sealed package |
| `seal_digest`, `seal_signer`, `seal_key` | only when `seal` is `sealed`: the seal digest, who sealed the package and the fingerprint of the key that signed, as `run.started` records them. An unsealed run has none of the three, so its manifest is byte for byte what earlier versions wrote |
| `inputs_digest` | the SHA-256 of the inputs blob |
| `execution` | `container` or `host`, as `run.started` records it |
| `images` | every image a container of the run (or of a child run) started from, each `{reference, id}` once, sorted: `reference` is the `image` of `step.command` (`shell` for the default image, `./images/<name>` for a package image), `id` its `image_id`. A command that could not start (no `image_id`) adds nothing; a host run without container steps has `[]` |
| `models` | the distinct models the steps used, sorted |
| `tool_versions` | `{}` in this version |
| `events` | the number of lines in `events.jsonl` |
| `head` | the hash of the last line |
| `status` | the final status |
| `gate` | `{status, category}` or `null` |
| `started_at`, `finished_at` | ISO-8601 UTC times |
| `traceworks_version` | the version that wrote the record |
| `signature` | `null`; manifests are not signed in this version. The seal of a package is signed, and the manifest only names it |

`traceworks verify` checks that the manifest names the same head and the same number of events as the log.
That is how a log that was cut short is found: the chain of the remaining lines still holds, but the manifest
does not match. It also writes the manifest again from the record and compares the two byte for byte, so a
manifest whose status, gate or any other field was changed does not match either. When a step's metadata blob
was deleted, the models cannot be told from the record, and the ones the manifest names are taken.

## The ledger

The ledger is a git repository at `<home>/ledger/`, or where `ledger_dir` in `traceworks.json` puts it (docs/cli.md,
"Places of the runs, the ledger and the registers"). It holds one commit per finished run, and one per write of a
register:

```
ledger/runs/<run-id>.json     a byte-identical copy of the manifest
ledger/registers/<key>.jsonl  the anchors of a register: one line {"seq":N,"head":"<sha256>"} per write
```

`traceworks run` refuses to start (exit 6) when the ledger cannot take an entry: git is missing, or the
repository cannot be created. If the manifest or the ledger entry still cannot be written when the run ends,
`traceworks resume` writes them later from the record, and adds nothing to the log.

The commit message is `run <run-id> <STATUS>`, or `register <key> seq <N>` for an anchor. Author and committer are
`Traceworks <traceworks@localhost.invalid>`.
The ledger is written with an empty git configuration, no hooks and no signing, so the operator's git setup does
not change it. Writers take `ledger.lock` (beside the ledger, or in the ledger folder when `ledger_dir` places it;
never committed), so two runs that end at the same time commit one after the other. An entry names its run by id,
not by a path, so the runs folder and the ledger can each be moved.

`traceworks verify` checks that the ledger's copy equals `manifest.json`. The ledger is a second place that
names the head of the run: to change a record unnoticed, you would have to change both.

An anchor of a register says that line `N` of its log had the hash `head` when it was written. The file keeps every
anchor, oldest first, and an anchor equal to the last one is not added again. `traceworks findings <key> verify`
checks each against the register's log (docs/registers.md, "The anchor"). A register's write does not wait for the
ledger: when the anchor cannot be committed, the write stays and the command says that it is not anchored.

## The register log

A register (docs/registers.md) has a log of its own, `registers/<key>/events.jsonl`, chained like a run's: the
first fields of every line are `seq`, `at`, `type`, `register` (the key) and `prev`, and `prev` is the same SHA-256
over the line before, with the genesis `traceworks-register-v1|<key>` before the first line. The blobs under
`registers/<key>/blobs/` are named and verified as a run's. `traceworks findings <key> verify` checks the chain, the
blobs and the recorded states.

| Type | Fields | Meaning |
|---|---|---|
| `register.created` | `workflow_id`, `category` | the first line |
| `register.requires` | `feature` | from this line on the register needs a version that knows `feature`. `decision_severity`: a decision covers the severity it was made on, reports are applied by `decision_on_higher_severity`, of two findings of one identity the most severe counts, and the states name the severity a decision was made on where the finding has another one. Written once, before the first line that needs it. A version that does not know the line (any before 2026-11-14) or the feature refuses to replay the log, and so writes nothing more into it: **a register that holds this line must not be written by an earlier version**, whose fold of the lines after it would differ from the states they recorded (docs/registers.md, "What a decision covers") |
| `register.repaired` | `discarded_bytes` | a tail without its line feed, left by a writer that was cut off, was discarded before this line |
| `report.applied` | `run_id`, `run_head`, `finished_at`, `report_blob`, `findings_blob`, `gone_blob`, `items_blob`, `items_complete`, `items`, `catalog_blob`, `decision_on_higher_severity`, `fact_names`, `counts`, `state_blob` | a run's report was applied (`decision_on_higher_severity` is the operator's setting it was applied with, `reopen` or `keep`; a line of an earlier version has no such field, and a replay applies it as that version did: every decision stayed, and of two findings of one identity the first counted; any other value is a broken line, which a replay refuses): the run and the head of its chain, the report text, the findings it stated (each with `item` when it names one), the findings it named as gone (`gone_blob`, an array of `rule_key` and `subject`; the field is absent when it named none, and in a line of an earlier version), the items it listed (`items_blob`, an array of `name`, `digest` and `judged`; `items_complete`, whether the report vouched for the whole list; `items`, the counts `listed`, `judged` and `settled` after the report; all three absent for a report without a list), the package's `catalog.json` it was applied with (`null` without one), the facts the run measured, the counts by kind of change (`not_mentioned` counts the open findings of a judged rule the report was silent about), and the state after it |
| `report.rejected` | `run_id`, `reason`, `errors` | a run's report changed nothing, and why |
| `finding.new` | `id`, `run_id`, `rule_key`, `subject`, `severity`, `title` | a finding seen for the first time |
| `finding.resolved`, `finding.reopened`, `finding.review_due` | `id`, `run_id`, `reason`; `decided_severity` and `severity` with the reason `higher_severity: ...` | a change of a known finding by a report. A reopening names why: `regression` (it was resolved), `seen_again` (it awaited verification), `worse: 320 LOC → 360 LOC` (an accepted risk whose evidence got worse), or `higher_severity: LOW → CRITICAL` (an accepted or dismissed finding stated with a severity above the one the decision was made on, with both as `decided_severity` and `severity`) |
| `finding.accepted`, `finding.dismissed` | `id`, `note`, `actor`, `until` (accepted), `evidence`, `severity`, `signature`, `state_blob` | a person's decision, and what it was made on: the `evidence` and the `severity` the finding had at that moment. A line of an earlier version has no `severity`, and a dismissal of one no `evidence`; a replay takes the severity the finding had at that line. `signature` is `null` until signed decisions exist |
| `finding.reopened` | `id`, `note`, `actor`, `reason: "manual"`, `signature`, `state_blob` | a person reopened a finding |
| `hook.ran` | `run_id`, `exit_code`, `duration_ms`, `stderr_tail` | the operator's hook ran for the run's diff |
| `register.unanchored` | `first_seq`, `last_seq` | a writer found the lines `first_seq` to `last_seq` after the last anchor in the ledger, before it wrote: no anchor vouches for them. It took them as they are; its own lines follow, and the anchor it writes covers all of them (docs/registers.md, "The anchor"). A version before this line existed cannot replay a log that holds it |

`state.json` is the fold of the log, as sorted compact JSON; the `state_blob` of every applied report and every
decision is that fold up to there, so a verifier can check each against its own replay. The verifier checks that
every blob a line names exists and hashes to its name, so a blob edited in place is found. A changed last line
that names no state, and lines cut from the end, are found by the anchors of the register in the ledger
(docs/registers.md, "The anchor"). No line of the register's log changed for that.

## The seal log

The seals of a home (docs/cli.md, "seal, unseal and seals") have a log of their own, `<home>/seals/events.jsonl`,
chained like a run's: the first fields of every line are `seq`, `at`, `type` and `prev`, and `prev` is the SHA-256
over the line before, with the genesis `traceworks-seals-v1|home` before the first line. Writers take
`seals/seals.lock`. `traceworks seals` and `seals log` check the chain, and so does every run before it starts.

| Type | Fields | Meaning |
|---|---|---|
| `SEALED` | `workflow_id`, `workflow_name`, `package_dir`, `digest`, `package_digest`, `signer`, `sealed_at`, `key`, `principals`, `actor`, `adopted` | a package was sealed: the workflow, where the package was, the seal digest and the package's own digest, the name the seal declares as its signer and the time it was signed, the fingerprint of the signing key and the names the allowed signers file gives it, and who ran `traceworks seal`. With `adopted: true` (absent otherwise) the seal was made elsewhere and a run of this home took it: `signer` and `sealed_at` are the seal's own, and `actor` is who started that run |
| `UNSEALED` | `workflow_id`, `package_dir`, `digest`, `note`, `actor` | the seal of a workflow was removed: the digest of the removed seal (`null` when the package carried no readable seal file), the `--note`, and who ran `traceworks unseal` |
| `REFUSED` | `action`, `run_id`, `workflow_id`, `package_dir`, `reason`, `detail`, `digest`, `sealed_digest`, `actor` | a `run`, a `resume` or an `images-build` (`action`; `run_id` only for a resume) was refused because of a seal: `reason` is a code, `detail` the message the person got, cut after 1000 characters, `digest` the seal digest of the package now and `sealed_digest` the one its seal names (each `null` when not known). `workflow_id` and `package_dir` are those of the package that was asked for, also when the seal of a package it calls is what did not hold; the `detail` names that package |
| `REPAIRED` | `discarded_bytes` | a tail without its line feed, left by a writer that was cut off, was discarded before this line |

The `reason` of a `REFUSED` line is one of:

| Reason | Meaning |
|---|---|
| `changed` | the seal digest of the package differs from the one its seal names |
| `not-sealable` | the package carries a seal, but a seal cannot cover it (a dynamic `workflow_id` or image, a child that cannot be loaded or lies under `.git/`, an image without a digest) |
| `seal-missing` | the home holds the workflow as sealed, and the package carries no seal file |
| `seal-superseded` | the seal is valid, but not newer than the last change the log holds for the workflow: an earlier seal was put back |
| `seal-unreadable` | the seal file is no seal, or the seal of another workflow |
| `signature-invalid` | the signature cannot be read, does not match the seal's fields, or was made with a key type that is not verified |
| `signer-not-allowed` | the allowed signers of the home do not allow the signing key |
| `signers-unreadable` | the allowed signers file cannot be read |
| `unsealed` | the package is not sealed, and `require_seal` asks for a seal |
| `seal-differs-from-start` | a resume: the run started with a sealed package, and the package is unsealed or sealed with another digest now |
| `sealed-since-start` | a resume: the package is sealed now and differs from the package the run started with |

Repeats are not recorded: a refusal whose workflow, reason and digest are those of the last line of the log adds
no line. So the log says that a refusal happened and when it first did, not how often.

The fold of the log says which workflows are sealed now: a `SEALED` line seals its `workflow_id`, an `UNSEALED`
line unseals it. It also keeps, for each workflow, the time of its last change: the latest of the times of its
`SEALED` and `UNSEALED` lines and of their `sealed_at`. A seal the log does not hold must be signed later than that
to be taken (docs/cli.md, "The seal of another home"). A line of a type this version does not know is a problem of the log, not a line to skip: what it
means for a seal is not known. A log that does not hold refuses every run of the home, and nothing is appended to
it; a refusal for that reason is therefore not in the log.

What the chain cannot show without an anchor outside the home: lines cut from the end, and a changed last line.
Cutting the last `SEALED` line makes the home forget that seal; the seal file in the package is still checked, and
`require_seal: always` refuses a package that lost both.

## Trust

A `sub-workflow` or `foreach` block may name its workflow as a path (`workflow_id`). The path may be absolute or
use `..`, and it can point anywhere on the machine. Run only packages you trust.
