# The command line

`traceworks` runs a workflow package, records what happens, and exits. There is no service and no database.

```
traceworks <command> [options]
traceworks --help
traceworks <command> --help
traceworks --version
```

Build it with `mvn -q package` and run `bin/traceworks` from the checkout, or run the jar
(`traceworks-cli/target/traceworks.jar`) with `java -jar`. It needs Java 21.

## A first run

A run runs its steps in containers, so it needs `podman` or `docker`, and the images its steps run in:

```bash
traceworks images build examples/hello               # once: builds the images hello uses (the shell image)
traceworks check examples/hello
traceworks run examples/hello --no-input             # parks at the approval, exit 5
traceworks approve <run> --answer Yes
traceworks resume <run> --no-input                   # finishes, exit 0
traceworks verify <run>
traceworks verify                                    # the ledger against the runs folder: what started and never ended
```

`<run>` is the run id or a unique prefix of at least four characters. A path to a run folder works too, when it is written as a path
(`/media/usb/<run-id>`, `./<folder>`): a bare name is always looked up among the runs of the home, never in the
working directory. A command given a folder reads everything from that folder, its texts too, and the commands it
suggests name the folder: the run id in the folder's record may be unknown to the home, or be another run of the
home. The
example is explained in `examples/hello/README.md`. On a machine without a container runtime, add
`--allow-host-execution` to `run` and `resume`: see "Host execution". What the containers protect, and what they do
not, is in docs/security.md.

## Commands

A flag takes its value as `--flag value` or `--flag=value`. `--home` and `--help` belong to every command.

### run

`traceworks run <package> [options]` runs a workflow package and prints what happens. `<package>` is a directory
that holds a `workflow.json`, or the `workflow.json` itself. A `workflow.json` or a `catalog.json` of more than 8 MiB
is refused before it is read.

| Flag | Meaning |
|---|---|
| `--input K=V` | an input of the run; repeatable; split at the first `=`. The input named like a model choice of the workflow picks one of its options ("Model choices") |
| `--inputs-from RUN` | take the inputs an earlier run was started with (its id or a prefix); an `--input` overrides one of them. Refused when that run's inputs are deleted from its record, or are not the bytes its record names (a changed blob); `run.started` records `inputs_from` |
| `--workspace DIR` | the directory the steps work in (default: the working directory) |
| `--source PATH\|URL` | work in a new checkout of this git repository instead of a workspace (see "Isolated workspace"); excludes `--workspace` |
| `--ref REF` | the branch, tag or commit of `--source` to check out (default: its `HEAD`); only with `--source` |
| `--allow-host-execution` | allow a host run where no container runtime works: shell steps without an image then run on the host (see "Host execution") |
| `--no-input` | never ask on the terminal; a question parks the run |
| `--no-check` | start the run although `traceworks check` reports an error for the package (see below) |
| `--allow-unsealed` | run an unsealed package without a terminal although the operator set `require_seal` to `"unattended"` (see "seal, unseal and seals") |
| `--fail-on-gate red\|yellow` | exit 7 when the run completes with a gate at or above this level |
| `--quiet` | print the summary only, not each event. The summary names where the run ran (`ran     in containers` or `ran     on the host`), which the start line says otherwise |

**The check before a run.** Before anything starts, `run` checks the package as `traceworks check` does (see
"check"). An `ERROR` refuses the run: `run` prints the errors and warnings on standard error, in the lines of `check`,
then `traceworks: the run is refused: check reports 2 errors; fix them, or start the run with --no-check`, and exits
6. No run exists then: nothing is recorded, no image is asked for and no `--source` is checked out. Without an error,
the warnings are printed once, on standard error, before the line `run started`, and the run starts; `INFO` findings
are left to `check`. `--quiet` does not hide them. The check knows the inputs of the run, those of `--input` and of
`--inputs-from`: a variable one of them sets is no `unknown-variable`. `--source`, `--workspace` and `--inputs-from`
change nothing else about it.

`--no-check` skips the check: the run starts whatever `check` would say. The engine's own run admission still refuses
a workflow it cannot run (exit 6, with a run that is recorded as `FAILED`; the `error` line of its summary holds the
refusal whole, every reason of it, where any other error is cut at 200 characters), and a block with a setting it cannot read
fails when it is reached; a placeholder that names nothing reaches the step as text. `resume` does not check: it runs
the definition recorded at the start, which the start checked or was told not to.

An `--input` that no block reads and that is not in `default_context` changes nothing in the run, which is mostly a
typo of its name. `run` names it on standard error and starts all the same, with and without `--no-check`:
`traceworks: warning: input "nme" is read by no block and is not in default_context, so it changes nothing in this run;
did you mean "name"?`. A workflow that starts a child workflow is not judged: the child gets every variable of its
parent.

**Stopping a run from its terminal.** While `run` or `resume` executes a run:

- **Ctrl-C once pauses.** The run finishes the step in flight, in a loop the item in flight, and parks: exit 5, and
  `traceworks resume <run>` goes on later (see "pause"). Standard error says `traceworks: pausing after the step in
  flight; Ctrl-C again cancels`. Nothing is cut and nothing runs twice.
- **Ctrl-C again cancels**, as `traceworks cancel` does: the step in flight is ended, the run ends `CANCELLED` (exit
  4) and cannot be resumed. Standard error says `traceworks: cancelling the run`.
- **The end of the process** (SIGTERM from `kill` or a service manager, a closed terminal, a shutdown of the
  machine) cannot wait for a step. The step in flight is ended, what is left of the run's containers is removed, and
  nothing more is recorded: no cancel and no end. The run is `INTERRUPTED`, and `traceworks resume <run>` continues
  it: the step that was in flight runs again, a loop only the items that did not finish. The process exits with
  the code of the signal (143 for SIGTERM).
- Ctrl-C before the run's first step (while a source is cloned, say) or after its last ends the command at once
  (exit 130), as for any command.

A step does not see the Ctrl-C of the terminal: its process (the client of its container, or a host command) starts
in a session of its own (`setsid`), so only Traceworks decides when a step ends. A process that Ctrl-C catches in
the instant it starts, before it has left the terminal's process group, has done nothing yet and is started again.
In `show` and `run.html` the loop a pause stopped is `RUNNING (paused)`: nothing runs, and the resume runs it again.
Where that is not possible, Ctrl-C
keeps its old meaning: there is no `setsid` program (macOS), or the Java runtime gives no signal handling. Then
Ctrl-C cancels the run at once, the end of the process cancels it too, and the command says so once on standard
error (`traceworks: note: Ctrl-C cancels the run here and cannot pause it (...)`); `traceworks pause` from another
terminal still pauses. A command started with SIGINT ignored (a background job of a script, `nohup`) does not see
Ctrl-C at all, as before.

**The seal.** When the run starts, the seal of the package is checked, whatever `--no-check` says: a package that
is sealed and whose seal does not hold starts no run (exit 6, see "seal, unseal and seals").

**Audit workflows.** A workflow with `metadata.audit` feeds a register at the end of every run: the findings its
report states are compared with what the register knows, the summary says what changed, and the next run gets
`{{ audit_findings }}` and `{{ previous_report }}`, and the same without their caps as files in `{{ register_dir }}`.
docs/registers.md describes it; `traceworks findings` reads it. `register_dir`, like `package_dir`, is no input: a
run started with `--input register_dir=...` is refused.

### resume

`traceworks resume <run> [options]` continues a parked, paused or interrupted run from its last checkpoint.

| Flag | Meaning |
|---|---|
| `--allow-host-execution` | the consent a host run needs to resume (see "Host execution"); a container run stays a container run |
| `--allow-changed-package` | resume although the package changed since the run started |
| `--allow-unsealed` | resume a run of an unsealed package without a terminal although the operator set `require_seal` to `"unattended"` |
| `--no-input` | never ask on the terminal; a question parks the run again |
| `--quiet` | print the summary only |

A resumed run of an audit workflow goes on with the register folder it was started with (docs/registers.md, "The
register folder of a run"), whatever the register holds by now. When a file of the folder is no longer the one
`run.started` recorded, the resume is refused before it records anything: `Run <run> cannot resume: the register
folder of the run changed: findings.jsonl is not the file the run started with (...)`.

A resume runs the package's `workflow.json` as the run started with it: the package digest is unchanged, so it is
the same definition. The copy of the definition in `run.started` is for the record's readers, with every secret's
value redacted, and never runs. If the package digest changed since, the resume is refused unless you pass
`--allow-changed-package`; it then runs the definition recorded when the run started, and the record says so. Such a
resume is refused when that recorded copy holds a `[redacted:<NAME>]` of a secret the run uses, because the copy is
then not the definition the run started with: restore the package as it was to resume the run.

A resume redacts with the values its secrets and credentials have when it resumes, read again from
`credentials.json` and the environment. If a value changed while the run was parked (a rotated key, a short-lived
token), resume with the value the run was parked with, or make sure no step after the park prints what an earlier
step stored: an old value is not known to the resumed run, and a later step that prints it is recorded in clear
(docs/security.md).

The resume warns when it may have happened. If `credentials.json` was modified after the run stopped (the time of
its `run.parked` line, or of its last line for an interrupted run) and holds the value of a secret the run declares
or of a credential of an AI provider it uses, it prints one line on standard error before the run goes on:
`traceworks: warning: credentials.json in the Traceworks home was modified after run <run> stopped (<time>), and it
holds the values of <NAME>, ...`, with what an old value would mean and a pointer to docs/security.md. It names
names only, never a value, and the record does not hold it; the resume then goes on as before. The warning compares
the file's modification time only, since the record keeps nothing derived from a value: it cannot see a value from
an environment variable, which has no such time, nor a value that was changed and changed back; a file that was
saved or touched without a change warns all the same. No warning is given when the file is not there, is older
than the stop, or holds none of the run's names. On a file system that keeps times by the second, a change in the
same second as the stop can go without a warning.

The record names the package directory, the workspace and the source of a checkout redacted too, when a secret's
value occurs in them. A resume works at their real paths, with each `[redacted:<NAME>]` replaced by the value the
secret has now. A package that is no longer there, or no longer at that path, refuses the resume (exit 6), and the
message names the directory as the record does.

A resume keeps where the run ran its steps: a container run resumes in containers, a host run on the host (also where
a container runtime works now), with the same consent as its start. Before anything else it removes what is left of
the run's containers (a run whose process was killed can leave one running) and of the networks of its AI steps'
egress proxies, and it runs the start check of "Host
execution" again. When it cannot confirm that no container and no network of the run is left (the runtime cannot list
or remove them, or none answers for a run that ran with one), it is refused (exit 6) and records nothing; the message names the commands that remove them:
`docker rm -f $(docker ps -aq --filter label=traceworks.run=<run>)` and
`docker network rm $(docker network ls -q --filter label=traceworks.run=<run>)` (or `podman`).

A run that was interrupted (its process was killed, or told to end: see "run") runs again the step that was in
flight, and the record says which. Untrusted values stay untrusted across a resume, and what a model wrote stays
protected: a shell line that names it is refused. A run started with `--source` resumes in its checkout; see
"Isolated workspace".

**A loop goes on where it stopped.** When the step that runs again is a `foreach`, it does not run every item again.
It takes over each item the record holds as finished, and runs the others. An item is taken over when all of this
holds; in every other case it runs:

- the record has its `foreach.item.finished` with the status `COMPLETED`, in the last pass of the loop (a pass that
  was itself resumed records again what it took over, so the last pass is complete);
- the item is the same: the same text, byte for byte, at the same index. An `items_command` runs again on a resume,
  so a list that changed runs the items that differ;
- its recorded output is whole: the blob is there, unchanged, and holds no `[redacted:` marker. An output in which the
  record redacted a secret's value is not the output the item produced, so that item runs again.

A failed or cancelled item, and the item that was in flight, run again. The loop's output is put together as in one
pass, so the steps after it read the same text and get the same step indices. No other block takes anything over: a
`while` that was in flight starts at its first iteration again, a `retry` at its first attempt, a `switch` at the
first block of its case, and a `sub-workflow` starts a new child run from the child's first block. Write such a step
so that it can run twice, or let it note in the scratch directory what it has done. The terminal and `run.html` mark a
taken-over item (`taken over from event <seq>, not run again`), and the record names the earlier event
(docs/events.md, `foreach.item.finished`). A loop that runs a workflow per item (`workflow_id`) takes nothing over:
a child run has no checkpoints of its own, so every item runs again.

What a taken-over item did is not done again: a command that wrote into the workspace or the scratch directory is
not repeated, and the resume trusts that what it wrote is still there.

A run that finished but whose manifest or ledger entry could not be written (for example because git was missing)
is completed by `resume`: it writes the missing manifest and ledger entry from the record, adds nothing to the log
and prints what it wrote. The same holds for a run that ended `FAILED` without a step because the ledger could not
note its start (docs/events.md, "The start entry"): `resume` writes its start entry too, and runs no step.

Before a resume of an unfinished run goes on, the ledger must hold the start entry of the run. A run without one
(a run of an earlier version, one whose entry was lost) gets it then; when the ledger cannot take it, the resume is
refused with exit 6 and records nothing. A resume of a run that is complete is refused, and so is a resume of a finished run
whose folder is not in the runs folder of this home (a copied folder): its ledger entry belongs to the home that ran it,
and `traceworks verify` is what checks a copy.

A resume checks the seal of the package again, as it is now (see "seal, unseal and seals", "What a run does with a
seal"). No flag gets a run past a seal that does not hold.

### approve and answer

`traceworks approve <run> --answer OPTION [--comment TEXT] [--agent NAME]` answers the approval a run waits for.
`OPTION` must be one of the offered options.

`traceworks answer <run> [--value TEXT] [--field NAME=VALUE ...] [--agent NAME]` answers a human input: `--value`
for a single input, `--field` (repeatable) for the fields of a form.

Both work on a parked run and on a running run. See "The inbox".

**An agent that answers names itself.** An AI agent that works with your user is your user to the machine, so the
actor alone would record its answer as yours. Both commands take `--agent NAME`, and without the option the
environment variable `TRACEWORKS_AGENT`, which a harness sets once for its agent; the option wins. It is the same
option, the same variable and the same name as for a decision in a register ("findings"): at most 64 characters,
ASCII letters and digits, `. _ - : / @ +` and single spaces. A blank or bad name of the one that counts is a usage
error (exit 2): nothing is recorded, and the question still waits. The record names the agent beside the actor
(`agent` in `approval.answered`), and the views say it after the actor:

```
$ traceworks approve 56e7c2a8-... --answer Yes --agent claude-code
The answer to step 1 of run 56e7c2a8-... is recorded through the agent claude-code; continue the run with traceworks resume 56e7c2a8-...
$ traceworks resume 56e7c2a8-...
12:34:56 step 1  answered: Yes by anna@laptop through the agent claude-code
$ traceworks show 56e7c2a8-...
Interventions
  2026-11-17T12:34:50Z  anna@laptop  answered step 1 (approval) via cli through the agent claude-code
```

Every way an answer reaches a run, and what it records:

| The answer comes from | `source` | `agent` |
|---|---|---|
| `approve` or `answer` on a parked run | `cli` | `--agent`, else `TRACEWORKS_AGENT` of the command |
| `approve` or `answer` on a running run | `inbox` | the same; it travels with the request, and the run's process checks the name again and refuses the request with a bad one (`request.refused`; the question stays open) |
| the prompt of a `run` or `resume` in the foreground | `terminal` | `TRACEWORKS_AGENT` of that process, and no option: whoever types there is the agent the harness named. A blank or bad value refuses the command before the run starts (exit 2); with `--no-input`, or without a terminal, the variable is not looked at |
| a resumed step that takes the answer given while the run was parked | `resume` | the agent of that answer, unchanged, whoever resumes |
| nobody: a default, a timeout | none | none |

The name is what the caller says, unsigned: an answer without it reads as a person's, and an agent that leaves it
out is recorded as you (docs/security.md, "Registers"). A start, a resume, a pause and a cancel take no agent: the
actor is all they name. A record written before this, and an earlier version reading a newer one: docs/events.md,
"approval.answered".

### pause

`traceworks pause [<run>]` asks a running run to pause. The run finishes what is in flight, then parks: it ends with
the status `PARKED`, its process exits with code 5, and `traceworks resume <run>` goes on later, in this terminal or
another, today or next week. Nothing is lost and nothing runs twice. Without `<run>` it takes the one run that is
running now; when none or several are running, it names them and exits 2.

What "in flight" means:

- **Between two steps.** The step that runs is finished, with its output and its checkpoint, and the next one does
  not start. A step is never cut: an AI call that takes ten minutes takes its ten minutes before the run parks.
- **Inside a loop.** In a top-level `foreach` the item that runs is finished, and the next one does not start. The
  loop's step is not finished; the resume runs it again and takes over the items that finished (see "resume"), so
  only the others run. A cancel that comes before the run has parked ends the loop's step `CANCELLED`, with the
  output of the items that finished, and the run `CANCELLED`.
- A loop that runs a workflow per item (`workflow_id`) and a child workflow do not park in the middle: a resume
  could not take over what they did. They run to their end, and the run parks after that step.
- A `while` and a `retry` do not park in the middle either: each is one step, and a pause is no cancel. The `while`
  runs until its condition or its limit ends it, the `retry` until an attempt completes or its attempts are used up,
  waits between attempts included, and the run parks after that step. The resume runs nothing of it again. The same
  holds for a `foreach` inside a child workflow. To stop such a step now, cancel the run.
- A pause during the last step parks nothing: the run ends.

In the terminal of the run itself, the first Ctrl-C does the same (see "run").

`pause` reaches the run through its inbox, as `cancel` does (see "The inbox"). It waits up to 10 seconds for the
run's process to record the request (`run.pause_requested`, with who asked), not for the run to park, and prints the
command that goes on. It exits 0 when the request is recorded, and 10 when it is not recorded within the wait. A run
that nobody runs (parked, interrupted or finished) cannot be paused: exit 6. A run that is already asked to pause
refuses a second request the same way.

A paused run waits for nothing but its resume: `approve` and `answer` are refused for it, `cancel` ends it, and
`show`, `runs --status PARKED` and `run.html` show it as parked, with the command to go on. The summary of the run
says where it stopped:

```
run 56e7c2a8-... PARKED
  record  /home/anna/.traceworks/runs/2026-11-13/56e7c2a8-...
  report  /home/anna/.traceworks/runs/2026-11-13/56e7c2a8-.../run.html (open it in a browser)
  ran     in containers

paused inside the loop of step 3: 22 of 110 items are finished, and the resume runs only the others.
go on with:
  traceworks resume '56e7c2a8-...'
```

A question that waits in the process (asked on the terminal, or one that never parks, see "Interactive and parked
runs") belongs to the step in flight: the pause takes effect once it is answered.

### cancel

`traceworks cancel <run>` cancels a run. A cancelled run cannot be resumed. It exits 0 when the run ends
cancelled. The step the cancel ended ends `CANCELLED`, not `FAILED`: a command it killed, an AI call it aborted, a
wait or a question it ended, a loop, retry or switch it cut short (see docs/events.md, `step.finished`). A JVM
error (`OutOfMemoryError`, `StackOverflowError`) during the cancel leaves the step `CANCELLED` too, but its output
keeps a line `Ended by <class name>: <message>` after the cancel text. A step that
completed before the cancel took effect stays `COMPLETED`, and no further step starts, whatever its block's
`on_failure` says. A cancel that arrives while a question parks the run wins: the run ends `CANCELLED`, not `PARKED`,
and the asking step ends `CANCELLED` with the output `cancelled while waiting for an answer`. The waiting step of a
parked run that `cancel` ends shows `CANCELLED` in `show` and `run.html` too.

A cancel is never stale. One that arrives while a `resume` still prepares the run (it holds the run, but has not
started a step) waits in the inbox, and the resume carries it out: it records `run.cancel_requested` with the
cancel's sender and ends the run `CANCELLED` without running a step, as a cancel of a parked run does. The waiting
`traceworks cancel` then exits 0 as for any other cancel. The same holds for a cancel that is still in the inbox of a
run nobody runs, because the shell that sent it gave up: the next `resume` or `cancel` records it.
A run whose cancel is recorded (`run.cancel_requested`, from a second Ctrl-C or the inbox) but whose process ended before
the run did is never continued either: the next `resume` or `cancel` ends it `CANCELLED` without running a step.

A cancel, and the second Ctrl-C, also end a wait of the running step: the wait of a `retry` between two attempts ends at once and
no further attempt starts, and a question that opens after the cancel (an approval whose AI actor was still answering)
is cancelled at once instead of waiting for its timeout. A command the step would start after the cancel starts
nothing and looks no image up: its `step.command` ends with exit code 130 and `the run was cancelled before the command
started`, never with an image that "is not present". An image lookup that times out (60 seconds) or goes beyond the
output limit is no missing image either: the command is refused with `the lookup of image <tag> failed: <why>`.

A cancel of a parked or interrupted run first removes what is left of the run's containers, found by their run label,
and is refused (exit 6, nothing recorded) when it cannot confirm that none is left, with the command that removes
them, as for `resume`. A run that ran with a container runtime (`container_runtime` in `run.started` or a
`run.resumed`) is refused the same way when no runtime answers for the cancel, for example while the Docker daemon
restarts: make the runtime answer, then cancel again. The second Ctrl-C and the end of the process (see "run") also remove them, after the runs of the process were told to stop; they cannot refuse,
so a container it cannot confirm gone keeps the run from listing its scratch directory (`step.files` records why). A
running run removes its own: a container whose step times out or is cancelled is ended and removed.

### runs

`traceworks runs [--status S] [--workflow ID_OR_NAME] [--limit N] [--long]` lists the newest runs, one per line: the first
8 characters of the run id, which `<run>` takes as long as no other run starts with them, the status, the workflow's
name and the start time (UTC). The default limit is 20.

`runs` reads the runs folder, so a run whose folder was removed is not in it. `traceworks verify` without a run reads
the ledger and names such a run, and every run that started and never ended ("verify").

```
c0c0f5eb  COMPLETED              Hello  2026-11-03T12:00:00Z
```

`--long` adds to each line the run's gate, its duration from start to end, the first 8 characters of the package
digest (`pkg`: the version of the workflow) and of the inputs digest (`inputs`), and the models its steps used; `-`
stands for what a run does not have. A `*` after a digest says that the run of the same workflow listed below has
another one: between those two runs the workflow, or the inputs, changed. `traceworks diff` says what else did.

```
b4ac6849  COMPLETED              Hello  2026-11-12T08:16:02Z  gate GREEN  1.4s  pkg ed4d01b5  inputs 9f470f56*  models -
5cf3bb0a  COMPLETED              Hello  2026-11-12T08:15:59Z  gate GREEN  1.3s  pkg ed4d01b5  inputs 981cfd4a  models -
```

### diff

`traceworks diff <runA> <runB> [--outputs]` compares two finished runs from their records and says whether run B
reproduced run A. It reads both runs and writes nothing. `<runA>` and `<runB>` are run ids, prefixes or run folders,
as everywhere.

```
Run A     5cf3bb0a-5e84-4685-8641-9ccff4e7ad51
Run B     b4ac6849-a685-41a8-ad56-456aabddf283

Header
  inputs digest        A 981cfd4a0a1eaedf4f0adba0b41a164af3cc500b6de585d82ba46827deddbdf1
                       B 9f470f56d870a4d2b3750f46e786c0734b7990b7b0291999899318602eed2f0c
  same: workflow id, workflow name, package digest, package commit, Traceworks version, execution, image shell, ai_network, models, status, gate

Inputs
  name                 A "Anna"
                       B "Bert"

Steps
  greet                          COMPLETED         output differs
  count                          COMPLETED         same output

differs: run B did not reproduce run A
  the inputs differ
  step greet: the output differs
```

**Header.** The workflow's id and name, the package digest and commit, the inputs digest, the Traceworks version, the
execution (`container` or `host`), each image a container started from with its id, the operator's `ai_images`,
`ai_network` and `ai_hosts`, the picked option of each model choice (`model choice <name>`), for an audit run the
head of the register's log it started from (`register <key>`: `at seq 17, head 6f48...`, or `before its first
line`), the models, the status and the gate. The register head is a field and never a difference by itself: the run
before moved it. It tells two runs that knew the same of earlier runs from two that did not. Two runs that picked different options did not reproduce each other: the inputs differ, and a line
names the choice and both picks. Only the fields that differ are printed, with both
values; one line names the fields that are the same, so two equal runs give a short output.

**Inputs.** The recorded inputs, key by key, the same way. A value the record redacted (`[redacted:<NAME>]`,
docs/events.md, "Redaction") is never printed: its line says `differs (a redacted value is not shown)`, and nothing
more. Two records that hold the same redaction count as the same, since the record holds nothing that could tell two
values of a secret apart. When a run's inputs blob was deleted, the inputs are compared by their digest only, and a
note says so.

**Steps.** One line per step: its status in both runs, and `same output` or `output differs`, decided by the hash of
the recorded output blob.

- A step is matched by its block name. A name that ran more than once in a run is matched by its occurrence (`poll`,
  `poll #2`), a block without a name by its position. A step that only one run has is listed as `only in run A` (or
  `B`).
- A `foreach` item is matched by its index; the items that differ are listed (another item, another status, another
  output), the others counted.
- A `retry` or a `while` is one step in the record, with one output; its attempts and iterations show as its AI
  calls, in order.
- The steps of a child run (`sub-workflow`, a `foreach` with `workflow_id`) stand under the step that started it, as
  `parent > child`: the child of a `foreach` item with the child of the same item, any other in the order the step
  started them.
- Of a step that started again after a resume, the latest attempt is compared.

**AI steps** are marked `[ai]`: a block that can ask a model, or a step that made an AI call. Each call is matched by
its `foreach` item and its order among the calls of that item, and printed with what both records hold: provider,
model asked for, model that answered, effort, tool policy, web mode, tokens and cost, whether the prompt has the same
hash, and whether the answer is the same. Of a step with more than five calls, the calls with nothing that differs
are counted, and a line sums the calls, tokens and cost of each run. The `in` tokens are what a call sent, the part
the provider's prompt cache held included (docs/events.md, "ai.response").

```
  review  [ai]                   COMPLETED         output differs
      AI call 1  same setup, prompt same, output differs
        A  CLAUDE  model opus  answered by claude-opus-5-5  tools read  web none  1840 in / 212 out tokens  cost 0.0310 USD
        B  CLAUDE  model opus  answered by claude-opus-5-5  tools read  web none  1840 in / 198 out tokens  cost 0.0302 USD
```

**`--outputs`** adds, below each step whose output differs, a line diff of the two outputs: `-` for a line only run
A has, `+` for one only run B has, two lines of context, the rest counted. The outputs come from the record as
`show --full` prints them, so with the same redaction and with control characters made harmless. The diff is
computed in memory and bounded: when the parts that differ are larger than 4 000 000 line pairs (2 000 by 2 000
lines, say), the command says so instead. A deleted output blob is named as such.

**Audit runs.** For two runs of an audit workflow that feed the same register (docs/registers.md), a section
`Findings (register <key>)` lists the findings that are `new`, `resolved` and `reopened` between the two: the state the
register recorded after run A's report compared with the state it recorded after run B's (`state_blob` of each
`report.applied` line). A decision a person took in between (`findings <key> reopen`) therefore shows too. When the
register did not apply a run's report, or this home does not hold the register (a copied run folder), the section
says `not compared` with the reason. The findings never change the exit code.

**The exit code** says whether run B reproduced run A:

| Code | When |
|---|---|
| 0 | the same: everything in the list below is equal |
| 11 | differs: something in the list below is not; the last lines name each reason |
| 6 | a run has not finished (`PARKED`, `RUNNING`, `INTERRUPTED`): it has no manifest and no ledger entry yet |
| 8 | a run's record cannot be read or does not verify (what `traceworks verify` checks: chain, blobs, manifest, ledger entry, start entry) |
| 2 | an unknown or ambiguous run |

`1` is not used for "differs", because it already means that a run completed with errors. What counts:

- the **package digest** and the **inputs** (the inputs digest);
- every **deterministic step**: its status and its output, and each `foreach` item of it. A step that only one run
  has counts too, and so does a step of which other commands had their output cut (`output_cut`, "Host execution"):
  `step build: the output was cut in run B (command 1: stdout after 5000 ms), not in run A`. A command is matched
  by its `foreach` item and its order among the commands of that item, in the latest attempt of the step, so a cut
  in item 2 against a cut in item 7 differs. This holds only between two records that can name a cut (`records` in
  `run.started` and in every `run.resumed`). A record of an earlier version does not say whether an output was cut:
  then the cuts are not compared, and when either run names one, a note says so (`note: run 5cf3... is of a version
  that does not record whether an output was cut: the cuts of the two runs are not compared`); This is strict on purpose: a deterministic step counts also when it comes after an AI answer that
  differed and only carries that answer on; the reason then ends with `(after an AI output that differed)`;
- the **setup of every AI call**: provider, model asked for, effort, tool policy, web mode and hosts, system prompt
  and prompt, and the model that answered when both calls report one. A different model or another prompt is another
  run. One exception: a prompt that differs **after** an AI output differed earlier in the run (in the order of run
  A's steps and calls) does not count. With an equal package and equal inputs, such a prompt differs because it holds
  the earlier answer, so the setup is the same and was only fed something else; the call says `prompt differs (it
  follows an AI output that differed)`. A prompt that differs with no differing AI output before it does count:
  something outside the record changed it, such as a file of the workspace.

What does not count, and is reported: the answer of an AI call, the output and status of an AI step, an output cut
of an AI step that differs (a note), an AI step or call that only one run has, and the other header fields (Traceworks version, execution, images, the operator's AI
settings, models, status, gate). So `0` means "the deterministic part ran the same, and the AI part was asked the
same way", not "the two runs printed the same".

### show and watch

`traceworks show [<run>] [--outputs] [--full] [--children]` shows a run: status, the workflow it ran (its package directory and
commit, its description, and one line per block of the recorded definition with the step it became, or `did not
run`), steps with their files, gate and the question it waits for. For a workflow with a model choice, a line
`Model` names the option the run picked, whether it is the default, and its provider and model (`Model     judge =
astra (picked): PI openai/gpt-6-astra`); `run.html` has the same as a row of its Workflow section. A line `Ledger` says
whether the ledger noted the start of the run (`Ledger    start noted in the ledger: yes (traceworks verify compares
the entry)`, or `no (recorded by a version that noted no start)`); it is what `run.started` says, `run.html` has the
same as the row `ledger`, and `traceworks verify <run>` is what compares the entry itself. For the run of
an audit workflow, a line `Register` names the head of the register's log the run started from and how many files
its steps were handed (`Register  hello at seq 17 (head 6f486c7c31a3), 4 files handed to the steps`, or `hello before
its first line` when the register did not exist yet); `run.html` has the row `register` with the whole head
(docs/registers.md, "The register folder of a run"). It names where the run's AI containers could
connect (`AI net`), and under each AI step the hosts its container could reach and every connection it asked the
egress proxy for; a denied one is marked `DENIED`, and the line `Egress` counts them for the whole run ("The network
of an AI step"). A command whose output was not read to its end is marked `output CUT` under its step, and the line
`Output` counts them ("Host execution", "A process the command leaves behind"). The line counts the whole run. The
steps of a child run that is listed carry the mark like any step, as far in as their step; the commands of an
earlier attempt of a step, and of the child runs a step with more than 10 of them does not list, are under no step,
so the line says how many of its count are those.
`--outputs` adds each step's output, cut at 2000 characters. `--full` adds it whole. A step's status is `COMPLETED`,
`FAILED`, `SKIPPED`, `CANCELLED` (the run's cancel ended it), `RUNNING` or `WAITING_APPROVAL`.

**What ran inside a step** stands under it, further in:

```
Steps
  0   route                    [switch]         COMPLETED         412 ms
      case fast|quick: case_fast [shell], notify [simple (PI)]
  2   child                    [sub-workflow]   COMPLETED         5210 ms
      child run 1a2b3c4d COMPLETED
        0   count_up                 [while]          COMPLETED         2204 ms
        1   deeper                   [sub-workflow]   COMPLETED         801 ms
            child run 77aa01c3 COMPLETED_WITH_ERRORS  gate RED (build)
              0   build                    [shell]          FAILED            640 ms
  3   each                     [foreach]        FAILED            9120 ms
      item 7/14  child run 5e6f7a8b FAILED
        0   work                     [shell]          FAILED            301 ms
      13 more child runs, all COMPLETED (show --children lists each)
```

- **A child run** (`sub-workflow`, a `foreach` with `workflow_id`) is one line under the step that started it: the
  first 8 characters of its run id, its status, its item for a `foreach` (`item 2/3`), and its gate when it reported
  one. Its steps follow, each as a step of the run itself is shown (its line, AI calls, egress, values, files, and
  its output with `--outputs`), six columns further in; a child of a child stands under its step the same way, to
  any depth. These are the child runs `run.html` draws under the same step, from the same reading of the record.
- **A step with more than 10 child runs** lists only those that did not end `COMPLETED`, at most 10, and counts the
  others by status. `--children` lists every child run of every step.
- **A `switch`** names the case that ran, as its `value` is written, and the blocks of that case with their types,
  from the definition the record holds; `case default` for the default blocks, `no case matched` when none ran
  (a case whose value is `none` is named as a case: the step's metadata says whether one ran, `caseMatched`). The
  blocks of a case are no steps of their own: their commands and AI calls are those of the switch's step. Under a
  switch of a child run only the case is named, since the record holds the definition of the run itself.

A run without child runs and switches is shown as before.

`show` and `run.html` also list the interventions: what people and processes did to the run from outside its
steps, with who and when. These are resumes (with the step that runs again and whether the package changed),
cancel requests, recoveries of a torn line, refused requests (with the reason) and answers given from outside the
run's process (`approve` or `answer` on a parked run, or through the inbox). In `run.html`, a step that ran again
after a resume also keeps its earlier attempts, with their commands and questions, and the resumed step shows the
answer given while the run was parked: who gave it, how, when and the comment.

`traceworks watch [<run>]` prints the events of a run until it finishes or parks, from its first event on. It
exits with the code the run's state has ("Exit codes"): 0 for a run that completed, 5 for one that parked.

Without `<run>`, `show` and `watch` take the one run that is running now, whose process is alive. Nothing is guessed:
when no run is running, the command says so and points to `traceworks runs`; when several are, it names each with
the first 8 characters of its id and its workflow. Both exit 2. A parked or interrupted run is not running; name it.

Text from the run is shown with control characters made harmless, so output cannot rewrite your terminal.

### verify

`traceworks verify <run>` checks the record of a run: the hash chain, the blobs, the manifest, the ledger
entry, the start entry of the ledger, the seal the run started with and, for the run of an audit workflow, its register folder (see docs/events.md). It prints one line for each and exits 8 when something does not hold. Bytes after the
last line feed of a finished run's `events.jsonl` count as a problem of the chain, since nothing appends after
`run.finished`. Those of a parked or interrupted run are a line its killed process did not finish; the next
`resume`, `answer` or `cancel` cuts them off and records them (`run.recovered`).

A deleted blob is reported and is no failure: deleting a blob is how something is taken out of a record. When the
deleted blob is a step's metadata, the models that step used cannot be told from the record any more, and the
manifest's `models` are checked as far as they still can be: the list must name every model the remaining metadata
names, at most one more model for each deleted metadata blob, sorted and without a duplicate. Any other list is a
`mismatch`.

It also works offline on a copied run folder: `traceworks verify /media/usb/<run-id>`. A run folder that is not in
the runs folder of the home is checked against its own chain and manifest; the ledger is `not checked`, with the reason,
because its entry is in the ledger of the home the run ran in.

The `seal` line is `unsealed` for a run of a package that was not sealed. For a run of a sealed package it checks
the seal that `run.started` holds against itself: the recorded signature must be a valid one over the recorded
fields of the seal, by the key the record names. That needs neither the package nor the home, so it works on a
copied run folder too. It does not ask whether the signer is allowed: the home that ran the run answered that when
the run started. To check the seal of a package as it is now, use `traceworks seals verify <package>`.

```
chain     ok (34 events, head 7e364c17...)
manifest  ok
ledger    ok
start     ok (the ledger noted the start, run.started b7a1c09e44d2)
seal      ok (sealed by anna@example.org, key SHA256:0pVQIJF1..., digest 5f2c...)
verified
```

The `start` line compares the start entry of the ledger (docs/events.md, "The start entry") with the first line of
the record. It is checked for a run in any state, also one that is running, parked or interrupted:

| Line | Meaning | Exit |
|---|---|---|
| `start     ok (the ledger noted the start, run.started b7a1c09e44d2)` | the ledger holds the entry, and it names this record's `run.started` line | 0 |
| `start     none: the run was recorded by a version that noted no start in the ledger (Traceworks 0.1.0)` | `run.started` has no `ledger_start`: a run of an earlier version, which has no entry | 0 |
| `start     missing: run.started says the ledger noted the start of this run, and the ledger holds no start entry of it` | the entry was taken out of the ledger; the ledger was deleted or replaced, or another `ledger_dir` was in force when the run started; the process was killed between `run.started` and the commit of the entry (a power cut at that moment: the honest cause); or the run ended because its start could not be noted. A `resume` writes the entry before it goes on, or completes a run that ended | 8 |
| `start     mismatch: the ledger noted the start of run ... with the run.started line ..., and the first line of this record is ...` | the record is not the one that started under this id, or its first line was changed | 8 |
| `ledger    changed: runs/<run-id>.json is not what commit 4f1d2c3b4a59 ("run ... COMPLETED sha256:...") left: that names the SHA-256 ..., and the ledger holds ...` | an entry of this run is not what the commit that names it left: a later commit changed or removed it, a second commit names it, or no commit does. It is not the entry of this run any more, whatever it holds now (printed on the `ledger` line, for the start entry too) | 8 |
| `start     NOT checked: ...` | the ledger exists and cannot be read, or holds something that makes git show another history than its commits (a replace ref, a shallow or grafts file, a second branch; docs/events.md, "What the ledger shows, and what it does not") | 8 |
| `start     not checked` | a copied run folder: this home's ledger does not speak for it | 0 |
| `ledger    not checked (the ledger does not know this run: it is of a version before this ledger began)` | a finished run of a version that noted no start, of which this ledger holds nothing (docs/events.md, "A new ledger"); the chain and the manifest are checked | 0 |

How a run of an earlier version is told from one whose entry was removed is the field `ledger_start` of its
`run.started`, which the hash chain covers. Such a run gets its start entry when it is resumed. Whoever can rewrite the whole record of a run that has not finished, and
the ledger's history with it, can make it read as an earlier version's; an entry that is still there is compared
whatever the record says.

#### verify without a run: the ledger against the runs folder

`traceworks verify` without a run checks the ledger against the runs folder of the home, both ways: what the
ledger says of every run it names, and what every run folder says the ledger should hold. It writes nothing.

```
$ traceworks verify
ledger    6 runs started, 3 ended; 1 run folder from before the ledger
started, not ended (3)
  3f2a91c4-6d1e-4c0b-9a57-1f0e8a2b7c11  2026-11-16T09:12:44.118Z  self-audit-security  folder INTERRUPTED
  7be01d22-0c4e-4a67-8a3b-52d1c9e7f004  2026-11-16T10:02:10.560Z  self-audit-security  folder GONE
  c40e7d1a-93b5-4e1f-a0d2-6b8f5e3c9a77  2026-11-16T11:30:02.004Z  self-audit-security  folder GONE
ended, folder gone (1)
  91aa0b6e-2f5d-4a0e-b1c3-7d9e6f4a2c18  COMPLETED
from before the ledger, not ended (1)
  0d2c44f1-7a9e-4b53-8c10-e5f6a7b8c9d0  folder PARKED
acknowledged (1)
  7be01d22-0c4e-4a67-8a3b-52d1c9e7f004  started, never ended, and the folder is gone
    by operator@box at 2026-11-16T15:04:10.220Z: deleted by hand, the disk was full
problems (2)
  c40e7d1a-93b5-4e1f-a0d2-6b8f5e3c9a77  started, never ended, and the folder is gone
  5b1f0e9d-8c7a-4f6e-9d5c-4b3a2f1e0d9c  the record says the ledger noted the start (PARKED), and the ledger holds no start entry of the run
verification FAILED: 2 problems; acknowledge one that has an honest cause with traceworks verify --acknowledge <run> --note "..."
```

- **started, not ended** lists every run with a start and no end in the ledger, oldest first, with the time and
  the workflow of the start entry and what the run's folder says: `RUNNING`, `PARKED`, `INTERRUPTED` (its process
  is gone; `resume` goes on), a final status (the run ended and its end entry could not be written; `resume` writes
  it), `GONE` (no folder of this run in the runs folder), `UNREADABLE` (a folder without a record that starts with
  this run's `run.started`: an empty folder made again counts as no folder), or `OTHER RECORD` (the first line is
  not the one the start entry names). A run stays in this list until it ends: a record cut to its first line reads
  `INTERRUPTED` like a crash and cannot be told from one, but it never leaves the list.
- **ended, folder gone** lists the finished runs whose folder is not there, with the status. The ledger still holds
  the manifest of each (`runs/<run-id>.json`), so this is no failure.
- **from before the ledger** counts the run folders of a version that noted no start, older than the ledger's first
  commit, of which the ledger holds
  nothing (docs/events.md, "A new ledger"), and lists those that have not ended. No failure: the ledger never knew
  them.
- **The records.** For every ended run whose folder is there, the check verifies the record against the end entry:
  the chain, the blobs, and that the manifest the record gives is the end entry byte for byte. So a record that
  was rewritten while its manifest was left alone fails here too (`the end entry of the ledger is not the manifest
  the record gives`). This reads every record and every blob of the home. `--quick` leaves it out and reads the
  first line and the manifest of each run only; the output then says `records   not verified (--quick)`, and only
  `traceworks verify <run>` checks a record.
- **in flight** appears for a run whose process is alive and has not written an entry yet: `its start entry is not
  written yet (run.started 12 s ago)` (it may wait for the ledger's lock), `its end entry is not written yet
  (run.finished 3 s ago)`, `its record has no line yet`. No failure, for five minutes after that line: then the
  missing entry is a problem that says `a process holds the lock of the run, and that line is 6 min old`. The same
  record without a live process is a problem at once.
- **acknowledged** lists every problem someone acknowledged, for good, with what the check said, who, when and the
  note. `[no longer what the check finds]` marks one the check no longer says in those words.
- **problems** is what fails the check, one line per problem, every problem of a run:

| Problem | What it means |
|---|---|
| `started, never ended, and the folder is gone (the ledger noted run.started ...)` | the case the start entry is for |
| `... holds no record that can be read` | a run folder, known to the ledger or not, without a record that starts with its `run.started` |
| `the record in the folder is not the one the ledger saw start` | another first line than the start entry names |
| `the record says the ledger noted the start (<status>), and the ledger holds no start entry of the run` | the ledger was deleted or replaced, another `ledger_dir` was in force, the entry was removed, or the process was killed between `run.started` and the commit |
| `the record ended <STATUS>, and the ledger holds no end entry of the run` | as `ledger    missing` of `traceworks verify <run>`; `resume` writes it |
| `starts/<run-id>.json is not what commit <id> ("<message>") left: that names the SHA-256 ..., and the ledger holds ...` | a later commit changed an entry, which Traceworks never does; also `commit <id> names <path>, and the ledger does not hold it`, `2 commits name <path>`, and for the acknowledgements and the anchors `commit <id> left <path> with a SHA-256 that is no beginning of the file the ledger holds`, `... no longer than the commit before it` and `the ledger holds more of <path> than commit <id> left` |
| `the ledger does not know this run, and its record ... is dated ..., after the ledger began (...)` | a record without `ledger_start` and without entries that is not older than the ledger: not a run from before it |
| `the manifest in the run folder (SHA-256 ...) is not the end entry of the ledger (SHA-256 ...)` | `manifest.json` and `runs/<run-id>.json` differ, or the folder has no manifest; `traceworks verify <run>` says which one the record gives |
| `the end entry ...`, `the start entry ...` | an entry that is no manifest of this run, has another status than its commit says, or does not name the `run.started` line of the start entry |

- A problem of the anchors of a register is listed under `registers.<key>` and acknowledged under that name.
- **`--json`** prints the same as one JSON object, in ASCII (every character from U+007F on is written as `\uXXXX`, so no text of a record or a note steers the terminal): `verified`, `records_verified`, the counts `started`, `ended`
  and `before_ledger`, and the arrays `open`, `ended_folder_gone`, `before_ledger_not_ended`, `in_flight`,
  `acknowledged` and `problems` (each problem with `run`, `code` and `text`; the codes are `lost`, `unreadable`,
  `other-record`, `no-start-entry`, `no-end-entry`, `entry-invalid`, `entry-changed`, `manifest-differs`,
  `record-broken`, `unknown-run`). `traceworks verify <run> --json` prints `verified`, `chain`, `manifest`, `ledger`,
  `ledger_reason`, `start`, `seal` and `register`. The exit codes are the same as without it.
- **Exit codes of `verify`:** 0 when nothing that was checked fails; 8 when anything does, when the ledger cannot
  be read, or when it is one this version does not accept (the reason is on standard error then, and nothing on
  standard output); 2 for a wrong call or an unknown run. **Exit 0 does not say that everything was checked.** Not
  checked, and said in the output each time: the records of the ended runs with `--quick`; a run in flight (its
  entry is on its way); a run folder from before the ledger (the ledger never knew it, its record is unproven and
  its chain is not checked here; `traceworks verify <run>` checks the chain and the manifest); an ended run whose
  folder is gone (the ledger holds its manifest, and nothing can be compared with it).
- **Registers.** `traceworks verify` without a run does not compare the log of a register with its anchors;
  `traceworks findings <key> verify` does. What it does judge is the anchor file itself (`registers.<key>`).
- The whole check fails, without a list, when the ledger cannot be read or holds something that makes git show
  another history than its commits: `traceworks: The ledger cannot be relied on ...: it has the replace ref ...`.
- It exits 8 when there is a problem nobody acknowledged; else 0.

**Acknowledging a run.** `traceworks verify --acknowledge <run> --note "why"` records that one problem of one run
has an honest cause: a folder deleted by hand, a `runs_dir` that changed, the run of another home that shares the
ledger. `<run>` is the run id or a prefix of at least four characters that one reported run has; the note is
required; a run the check does not report cannot be acknowledged, and there is no way to acknowledge several runs
at once. The ledger gets one commit for it (docs/events.md, "Acknowledging a run") that names every problem the
check reports of the run at that moment, the command prints the check again, and from then on those problems are
listed under `acknowledged` and do not fail the check. The acknowledgement covers them as the check worded them, and
every sentence holds what tells its case from another (a SHA-256, a commit, a line of the record): a problem that
comes later, also one of the same kind with another content, fails the check again.

What the check cannot find is said in docs/events.md, "What the ledger shows, and what it does not".

No command of Traceworks removes a run folder. Whoever removes one by hand leaves the ledger's entries: this command
still names the run.

### render

`traceworks render <run>` writes `run.html` of a run again and prints its path. `traceworks run` already writes
it when the run ends or parks.

Open `run.html` in a browser. A browser installed as a snap (Ubuntu's Chromium and Firefox) cannot read files under
`~/.traceworks`, since snaps see no hidden directory of the home: copy the file somewhere visible, or use a browser
that is no snap.

The page starts with the run's header and a "Workflow" section: where the package lies (directory, commit, digest),
the workflow's description and a table of its blocks as the record holds them (name, type, provider and model, the
first line of the command or prompt), each linked to the step it became. Then come the steps, each with its
commands, AI calls, output and the files it left in the scratch directory.

`run.html` is one self-contained file. It has no scripts, and its Content Security Policy blocks every external
load. All text is HTML-escaped, and each content block is cut at 65 536 characters with a note that names the
blob. A character that changes how the page reads is shown as its code point, such as `[U+202E]`: in a name, an
option, an answer command, a gate category, an image or a path every control, format (direction included) and tag
character, and in content (an output, an answer) the direction characters; content keeps its line breaks and tabs.

### check

`traceworks check <package>` checks a package without running it and prints the findings, one per line: the level
`ERROR`, `WARNING` or `INFO`, the rule, the block (its name and index) and what was found. A finding on the workflow as
a whole or on its package's images names no block:

```
ERROR   unknown-requirement          requires names "time.travel", which Traceworks does not know ...
ERROR   missing-required-config-key  build (block 0): required key command or container_args is missing ...
```

It exits 6 when there is any error, and `traceworks run` starts no run of a package with one (see "run"). Every
reason the engine's run admission would refuse the run for is an error, such as a missing
requirement, an AI step's tools or a package image in a child of another package (`child-package-image`; see
"Requirements" and "AI steps"). The rules judge the package's own workflow; of the child workflows it reaches by a
literal `workflow_id`, `check` reports only their package images, their questions (`nested-wait-point`) and, for
`protected-value-in-shell` and `protected-value-outside-shell`, whether one of them asks a model; the shell lines, tool
arguments, mounts and `workflow_id`s of a child that take values its parent hands over are judged when the child runs ("Values: how a protected value reaches a command"). A child
that names Codex, which refuses the run at its start ("AI steps"), is reported when you check the child's package. A
Dockerfile under the package's `images/` whose `FROM` names no `@sha256:` digest gets the warning
`unpinned-base-image`, one without the label `org.opencontainers.image.description` the warning
`image-without-description` (the record and `run.html` say what an image holds only from that label), and a directory
there with a Dockerfile whose name is not a valid image name the warning `invalid-image-name` (see "images").

For a package that is sealed, `check` also looks at the seal as a run would, and records nothing: a package that
changed since it was sealed gets the warning `sealed-package-changed`, a sealed package that a seal cannot cover
(see "seal, unseal and seals") the error `sealed-package-not-sealable`, and a seal that does not hold for any other
reason (its signer is not allowed, its signature does not match, the seal log of the home does not hold) the warning
`seal-invalid`. A run of the package is refused in each case, whatever the level.

A placeholder that names nothing stays in the text as it is written, and the step runs with it. `check` reports the
commonest ways to write one, two more mistakes of the same kind, and free text in shell code:

| Rule | Level | What it reports |
|---|---|---|
| `unknown-block-reference` | `ERROR` | `{{ block.<name>.output }}` where no block has that name, at the top level or in a `switch` case |
| `unknown-block-property` | `ERROR` | `{{ block.<name>.<property> }}` of a block that exists, with a property other than `output`, the only one there is (`{{ block.build.outptu }}`) |
| `step-output-out-of-range` | `ERROR` | `{{ step_N_output }}` with an N no step gets. Steps are numbered from 0, one per top-level block: in a workflow of two blocks the last one is `{{ step_1_output }}`. Judged only where the numbers are known before the run: a `foreach`, `while`, `retry`, `conditional` or `switch` block takes one further number for each inner step, so when one of them stands before the last block, no `step_N_output` is judged |
| `duplicate-block-name` | `ERROR` | two blocks of one name that can both run in one run: `{{ block.<name>.output }}` would hold the output of whichever ran last, and a `compress` block refuses the name as its source. The blocks of `switch` cases share the names of the top-level blocks. The same name in two cases of one `switch` (or in a case and its `default`) is fine, because only one of them runs |
| `empty-workflow` | `ERROR` | a workflow without blocks: a run of it does nothing and ends `COMPLETED` |
| `protected-value-in-shell` | `ERROR` | a protected placeholder in shell code (`command`, `items_command`, `run_if`, a shell `condition`, `then_command` or `else_command`), wherever it stands: bare, inside quotes, in a comment or a heredoc, with or without `shell_escape_variables`. A run refuses such a line ("Values: how a protected value reaches a command", below), so Traceworks refuses the workflow. Protected, as far as `check` can tell: the outside text `previous_report`, `audit_findings`, `audit_items` and `previous_chain_output`; the output of a block that asks a model (`simple`, `agentic`, `compress-ai`, a wrapper in AI mode, a block with such a step nested in it, a step an AI actor decides, a `sub-workflow` or `foreach` whose child workflow, named literally, has one); the output of a top-level block after the first of them, and of a block nested after a nested one (in a loop, of any block nested next to one); a `{{ step_N_output }}` after a `foreach`, `while`, `retry`, `conditional`, `switch` or `sub-workflow`, where the numbers are not known before the run, and a `{{ previous_output }}` in a nested block, both when a model is asked before the block. Named outputs are found through `{{ block.<name>.output }}`, `{{ previous_output }}` in the block's own fields and `{{ step_N_output }}` where the numbers are known. Not seen by `check`, and refused by the run: a loop item, a variable, an untrusted input, and the shell lines of a child workflow that take values its parent hands over. Correct: `"values": {"REVIEW": "{{ block.review.output }}"}` in the block's config, and `"$REVIEW"` in the command |
| `protected-value-outside-shell` | `ERROR` or `WARNING` | a protected placeholder in one of the three places besides shell code that take none: an argument of a tool (`container_args` of a `shell` block), an entry of `mounts` (anywhere in it, the container side included; of a `shell` block, also in a `switch` case and in tool mode, of a `retry`, `while`, `conditional` or `foreach` in shell mode, and of a `simple` block in shell mode in a `switch` case), and the `workflow_id` of a `sub-workflow` or `foreach`. A run refuses the step when it reaches it ("Values: how a protected value reaches a command", below), and the finding says which runs do. An error where every run that reaches the step refuses it; a warning, which names the runs, where only some do: model text counts from the first call of a model on, so the output of a step after a model step that a run may skip (one under `run_if`, in a `switch` case, a `foreach` that may have no item, a `compress-ai` that may refuse its input, a child workflow) is protected only in the runs in which that step asks, and any other run takes the value as it is. A `simple`, `agentic`, `retry`, `while` or `conditional` step in AI mode without `run_if` asks in every run that gets past it. Outside text is reported only where a run sets it: `previous_report`, `audit_findings` and `audit_items` in an audit workflow, `previous_chain_output` in the check before a run that gets it as an input; anywhere else the placeholder stays text, the run takes it, and `chain-only-placeholder`, `audit-only-placeholder` or `previous-report-outside-audit` says so. The `mounts` of a top-level `shell` command without a `container_image` are read in a container run only, so the error there is about container runs: a host run starts the command on the host without its mounts, and a workflow meant for host runs alone needs `--no-check` or, better, no such mount. Model text is found as for `protected-value-in-shell`, which reports an error wherever a run may protect the value. In a `workflow_id` every `{{ previous_output }}` and `{{ block.<name>.output }}` is reported, in a workflow without a model too: the run takes no step output there. Not seen by `check`, and refused by the run: a loop item, a variable, an untrusted input, what a parent hands to a child, and a block output that only a run with untrusted input protects. Not judged, because no run reads it: `container_args` or `workflow_id` of a block in a `switch` case, `mounts` of a wrapper in AI mode. Correct: for a tool, a shell step before it that writes the value to a file (`"values": {"ANSWER": "{{ block.ask.output }}"}` and `printf '%s' "$ANSWER" > "{{ scratch_dir }}/answer.txt"`), and the file's path in the arguments; for a mount and a `workflow_id`, the path or the workflow written out, or a variable that `default_context` or an input of the run sets |
| `invalid-value-name` | `ERROR` | `values` that a step cannot take: a name that is no variable name (`[A-Z_][A-Z0-9_]*`) or that the run or bash reserves (what a secret may not be called; every variable bash sets or uses, which are the sections "Bourne Shell Variables" and "Bash Variables" of its manual, `_` and the names that start with `BASH`; a secret of the same block), a value that is no text, `values` that are no object, and `values` on a tool step (`container_args`), which runs no shell. The step fails when it is reached, before anything runs |
| `value-name-configures-program` | `WARNING` | a name under `values` of a family that programs read as their configuration: `GIT_*`, `*_OPTIONS`, `LD_*`, `PYTHON*`, `NODE_*`, `JAVA_*`. The variable is not exported, so no program reads it by itself; a command that exports it (`export NODE_OPTIONS`) hands a protected value to such a program. Choose another name |
| `unused-value` | `INFO` | a name under `values` that no shell line of the block uses as `$NAME` or `${NAME…}`: the variable is set and nothing reads it |
| `quoted-placeholder` | `WARNING` | a step output the run inserts as it is, inside quotes: an apostrophe or a quote in the value ends them. In a block with `shell_escape_variables`, also any placeholder in single quotes, where the two pairs of quotes cancel out. (A protected placeholder is `protected-value-in-shell`, wherever it stands) |
| `heredoc-placeholder` | `ERROR` or `WARNING` | a step output the run inserts as it is, in the body of a heredoc. An error where an interpreter reads the body as its program (`python3 - <<'PY'`, `… \| sh`): the value turns into code. A warning under an unquoted delimiter (the shell runs `$(…)` in the value), and for any placeholder in a body of a block with `shell_escape_variables` |
| `unknown-model-backend` | `ERROR` | a `model` written as a literal on a step of the provider `PI` (or the workflow's `model` such a step takes) that names a backend other than `anthropic`, `openai`, `google` and `openrouter`, and none that `local_models` in the `traceworks.json` of the home declares, such as `ollama/llama3` in a home without an `ollama` entry: Traceworks cannot tell which credential the backend takes, and the run is refused at its start. A model that names a declared backend whose `models` do not list it is reported under the same rule ("Local models"). The `model` of an option of a model choice whose provider is `PI` is judged the same way, for every option (`model_choices.<choice>.options.<option>`). A model with a placeholder is `placeholder-in-model` |
| `placeholder-in-model` | `ERROR` | a `model` with a placeholder, the workflow's or that of a block that makes an AI call, such as `"model": "{{ m }}"`: no run resolves it, so the CLI would be asked for a model of that text. Traceworks refuses the workflow. A model a run may pick is a declared choice ("Model choices") |
| `invalid-model-choice` | `ERROR` | a `model_choices` declaration that is not understood completely (a choice that is no object, a key other than `default` and `options`, an option without `provider` or `model`, a placeholder in either, the provider Codex, a name that is no plain name or one a run sets itself, a `default_context` entry of the choice's name), a `default` that is none of the options, a block whose `model_choice` names no declared choice, and a block that names `provider` or `model` next to `model_choice`. Traceworks refuses the workflow |
| `unused-model-choice` | `WARNING` | a declared model choice that no block refers to with `model_choice`: a pick of it changes nothing |
| `unknown-variable` | `WARNING` | `{{ name }}` that nothing sets: it is not in `default_context`, no block sets it (the `variable_name` of an `approval`, a `human-input` or its `fields`, or a `foreach`, their defaults `approval_answer`, `user_input`, `item` and `item_index`, a `context` entry of a `foreach`, `agentic_goal_achieved` and `agentic_iterations`) and Traceworks does not provide it (`previous_output`, `package_dir`, the run variables such as `project_path` or `workflow_run_id`, and the three variables of each declared model choice). A warning, because a workflow cannot declare its inputs: the variable may come from `--input`, or from the parent run when the workflow runs as a child. A near name is suggested. Not judged: a key that is no plain name, such as `{{ item.id }}` or `{{ .State.Running }}` (the template of another tool in a shell command) |

`check` judges a package on its own, so `unknown-variable` names every variable that only an input sets; the check
before a `run` knows the run's inputs and leaves those out. A child workflow checked on its own gets the warning for
every variable its parent hands to it.

**Values: how a protected value reaches a command.** A shell line inserts the value of a placeholder as it is, or
with `shell_escape_variables: true` in single quotes. A protected value is never inserted: an untrusted input,
platform data, every output of a run that carries untrusted input, and, from the first call of a model on, every
step output recorded since and every variable made from one (a `step_N_output`, a `foreach` item from an
`items_command`, an AI actor's answer to a `human-input`, an approval's `auto_answer` filled in from a placeholder).
The last holds for the rest of the run, for the shell steps after the call too (one of them can print what the model
wrote), in a child workflow the values are handed to, for the output of a child workflow that asked a model, and
after a resume. What a step recorded before the first call is inserted as before, a run without such values runs as
it always did, and a trusted write (the static `items` of a `foreach`, a person's answer) takes the mark off a
variable.

A shell line that names a protected placeholder is refused, wherever the placeholder stands, with
`shell_escape_variables` too. `check` reports it before a run where the workflow shows it
(`protected-value-in-shell`); otherwise the step fails before any command runs (a `run_if` test and an
`items_command` included), and the run ends with the error:

```
ShellLineRefusedException: shell line refused, nothing ran: {{ item }} is text a model wrote or text from outside
the run, and such a value never goes into shell code, quoted or not. Hand it to the command as a variable: put it
under the block's values, for example "values": {"ITEM": "{{ item }}"}, and write "$ITEM" where the placeholder
stands
```

The way in is the block's `values`: an object from a variable name to a template, which takes any placeholder.

```json
{"type": "shell", "name": "store", "config": {
  "values": {"REVIEW": "{{ block.review.output }}", "TITLE": "Review of {{ name }}"},
  "command": "printf '%s\\n' \"$REVIEW\" > \"$TITLE.md\""}}
```

The run fills each template in and sets the variable before the block's shell lines run: its `command`,
`items_command`, `run_if`, a shell `condition` and its branches, and the inner shell steps of a wrapper, filled in
anew for each line (a value of a `foreach` may name `{{ item }}`). The variables are the shell's own and not
exported, also under a name the step's environment already exports: no process the command starts inherits one
unless the command exports it knowingly (`export NAME`). A name a program reads as its configuration (`GIT_*`,
`*_OPTIONS`, `LD_*`, `PYTHON*`, `NODE_*`, `JAVA_*`) then hands the value to that program, so `check` warns about it
(`value-name-configures-program`).
The value is no part of the text bash parses: Traceworks writes its own lines first, which carry the value encoded
in letters, digits and `\xHH`, and bash decodes it into the variable. Every byte arrives, trailing newlines
included, up to the output limit; a NUL is taken out; and a value that was not delivered (the variable does not
hold exactly what was sent) ends the shell with exit code 74 before the command's first line. The engine knows such
an end from a command that exits 74 by itself, by a token in the line the shell writes: the command counts as not
run, and a `run_if`, a shell `condition` or the condition of a `while` fails its block instead of reading it as
"false". A step with `values` needs bash 3.1 or later; an older one ends the same way. A name matches `[A-Z_][A-Z0-9_]*` and is none the run or bash reserves (`invalid-value-name`). A tool step (`container_args`) runs no shell and takes no values.

Three places besides a shell line take no protected value: the arguments of a tool, where it would choose what the
tool does, a `mounts` entry, where it would choose what the container sees, and a `workflow_id`, where it would
choose which workflow runs. A `mounts` entry is refused as a whole, whichever side of it names the value.
`check` reports each before a run where the workflow shows it (`protected-value-outside-shell`: an error where
every run refuses, a warning where only some do); otherwise the run refuses the step when it reaches it, and
nothing starts:

```
container_args must not contain untrusted values (untrusted input, tainted block output, platform data, text a
model wrote): {{ block.ask.output }}
mounts must not contain untrusted values (untrusted input, tainted block output, platform data, text a model
wrote): {{ block.ask.output }}:/data:ro
workflow_id may only use trusted context variables, not {{ block.ask.output }}
```

A tool reads such a value from a file that a shell step wrote, and its arguments name the file:

```json
{"type": "shell", "name": "hand_over", "config": {
  "values": {"ANSWER": "{{ block.ask.output }}"},
  "command": "printf '%s' \"$ANSWER\" > \"{{ scratch_dir }}/answer.txt\""}},
{"type": "shell", "name": "scan", "config": {
  "container_image": "./images/tool",
  "container_args": ["scan", "{{ scratch_dir }}/answer.txt"]}}
```

The path of a mount and a `workflow_id` are written out, or come from a variable that `default_context` or an input
of the run sets. A `workflow_id` takes no `{{ previous_output }}` and no `{{ block.<name>.output }}` in any run,
protected or not.

The record keeps the command as the workflow wrote it and, next to it, the values by name (`step.command`,
docs/events.md); `show` names a step's values, and prints them with `--outputs`; `run.html` shows each under its
command; `describe` lists a block's value names.

What the command does with a variable is its author's. `"$REVIEW"` is one argument; an unquoted `$REVIEW` is split
into words and matched against file names; `eval`, `bash -c "$REVIEW"` and bash's arithmetic (`$(( REVIEW ))`,
`[[ $REVIEW -eq 1 ]]`) evaluate what they are given (docs/security.md, "A model's answer in a shell command").

An audit workflow (`metadata.audit`, docs/registers.md) is understood completely or refused: a block with
`enabled: true` but a missing, blank or non-string `key`, `category` or `report_block`, a `schema_version` other than
the number 1, or an `enabled` that is no boolean is the error `invalid-audit-config`; a `key` that is no register key
(`[a-z0-9][a-z0-9._-]{0,127}`) is `invalid-audit-key`; a `report_block` that names no top-level block is
`report-block-missing`; a `report_block` that runs a child workflow (`sub-workflow`, a `foreach` with
`workflow_id`) is the info `report-from-child-sets-no-gate`, since a gate marker in the child's output sets no gate
of the run ("When a step fails"); a `catalog.json` the register cannot read is `catalog-invalid`; `{{ previous_report }}`
in a workflow that feeds no register (no `metadata.audit`, or `enabled: false`) is `previous-report-outside-audit`,
and `{{ register_dir }}` there is `register-dir-outside-audit`; and `{{ register_dir }}` in the prompt of an AI step
is `register-dir-in-ai-prompt` in every workflow, since the container of an AI call does not get the folder. Each
of them refuses a run at its start: a broken audit block is never ignored, and only an audit workflow has a previous report
and a register folder (docs/workflow-format.md).

Every block setting is checked against the type and range the run accepts, at the top level and in nested blocks (a
`switch` case, `default`), and so are the keys of `validation`. A value of the wrong type or out of range is the error
`invalid-config-value`, worded as the run words it, such as `shell: 'timeout_seconds' must be a whole number between 1
and 86,400, found the text "5" — the step fails when it is reached`. Booleans are `true` and `false`, never the text
`"true"`; whole numbers are numbers, never text, and never a placeholder (the run resolves none in a number); lists of
texts (`mounts`, `options`) are lists. The ranges: `timeout_seconds` 1 to 86,400 (`compress`: 1 to 600),
`iteration_timeout_seconds` 1 to 86,400, `total_timeout_seconds` 1 to 604,800, `max_iterations` 1 to 10,000,
`max_retries` 0 to 100, `delay` 0 to 3,600, `max_consecutive_failures` 0 to 10,000, `timeout_minutes` 0 to 10,080 (0
times out at once), `max_repeated_actions` 1 to 10,000, `max_total_tokens` 1 to 1,000,000,000 (the budget counts the input the prompt
cache did not serve and the output, not the cached input that `show` and `run.html` include in a call's tokens in;
docs/events.md, "ai.response"). A key of `validation`
that the run does not read is the warning `unknown-config-key`, as for a block. A setting the run never reads
(`label`, `description`) is reported too, and the finding says the run does not read it. `untrusted_input` is the one exception at run
time: the run still takes the text `"true"` (any case) as `true`, and `check` reports it all the same.

At run time, every setting is read the same way: a value of the wrong type or out of range fails the block before it
runs anything, with that message as the step's output and the run's error (`InvalidSettingException: ...`), whatever
its `on_failure` says. A `null` value counts as absent: the default applies. `traceworks run` refuses a package with
such a value before it starts (see "run"); a run started with `--no-check`, and a child workflow, which `check` does
not judge by these rules, may still carry one, and fails at the block that reads it.

The stages of a `compress` block are checked as the run checks them, before any stage runs. An error (`invalid-config-value`)
is a `stages` list that is empty or no list, a stage that is no object or has an unknown `op`, a stage that expects JSON
after one that yields text, a stage setting that is missing or invalid, and a `max_chars` or `timeout_seconds` of the
block out of range: the step fails when it is reached. A setting the stage's `op` does not read is the warning
`unknown-config-key` (the run ignores it; a near spelling is suggested), and a `lines.dedupe` flag that is not `true` or
`false` is the warning `invalid-config-value` (the run counts it as off). A `compress-ai` block's `max_chars` (500 to
200,000) and `timeout_seconds` (1 to 86,400) are checked the same way.

### describe

`traceworks describe <package>` shows what a workflow will do before it runs. It reads the package as `check` and
`run` read it and prints it as text; it starts nothing (no container, no AI call), writes nothing and needs no
container runtime:

```
Workflow  Hello (b1445bda-e182-479a-bf85-f5b519d68ad2)
  A small workflow that greets, counts the files in the workspace, asks for approval and says it is done.
Package   /srv/traceworks/examples/hello
Digest    5620ebcc7d1821fd1dd0f448715c1c0c4a3438ade2034011ed9d82fed3ad6ee9
Commit    3684a05ff2909c495c6853ca8fb801555c574c9d

Inputs
  name = world
Requires  nothing

Blocks
  0   greet                    shell
      echo "Hello, {{ name }}"
      shell: network none, workspace read-only, image: the run's shell image
  1   count                    shell
      bash "{{ package_dir }}/scripts/count-files.sh"
      shell: network none, workspace read-only, image: the run's shell image
  2   publish                  approval
      Publish the greeting "{{ block.greet.output }}"?
      person: a person is asked
  3   done                     shell
      echo Published: {{ block.greet.output }}
      shell: network none, workspace read-only, image: the run's shell image

4 blocks: 0 AI steps, 0 shell steps with network, 1 step that asks a person
The operator's settings decide the rest: traceworks.json (ai_network, ai_hosts, ai_images, the output limits, ...
Nothing was checked: 'traceworks check <package>' says what a run would refuse.
```

- **The header.** The workflow's name, id and description, the package directory, the package digest and, for a
  package in a git checkout, the commit (with `(with uncommitted changes)` when it has any). The digest is the
  `digest` a run of the unchanged package records in `run.started` and the `package_digest` of its manifest, so the
  page of a description and the record of a run can be matched.
- **The inputs.** `Inputs` lists the keys of `default_context` with their defaults: the variables a run starts with
  unless `--input` sets them. A default that is no text is shown as JSON. `Requires` is the `requires` list. `AI` is
  the workflow's own `provider`, `model` and `effort`, and `Instructions` the first line of its instructions, each
  only when the workflow has it. `Audit` names the register an audit workflow feeds (`metadata.audit`: key, category,
  report block), or says what is wrong with the audit block. For a valid one the line ends with what every step of
  the workflow may read besides what its own block says: `its shell and tool steps read the register's files in
  {{ register_dir }}, its AI steps do not` (docs/registers.md, "The register folder of a run").
- **The model choices.** `Model choices` lists each choice the workflow opens ("Model choices"): how a run picks
  (`--input <choice>=<option>`), the default, every option with its provider and model, and the blocks that refer to
  it. A workflow without a choice has no such section.
- **The blocks**, in order. The first two lines of a block are the ones `show` prints in its "Workflow" section: the
  position, the name, the type with the provider and model the block names (`model choice <name>` for a block that
  refers to a choice), and the first line of its command or prompt, cut at 120 characters. The `ai:` line of such a
  block says what it runs with unless the run picks: `model choice judge: fable (provider CLAUDE, model
  claude-fable-5-2) unless the run picks opus or astra`. Then what the block may touch, each line only when it applies:
  - `shell:` for a block whose command runs as a shell step (a `shell` block, a wrapper with `execution_mode: shell`,
    a `simple` block of a switch case in shell mode): `network none` or `network open` (`network: true`),
    `workspace read-only`, or for a writable one whether its `.git` is writable too in this home
    (`workspace writable, its .git read-only` or `workspace writable, with its .git (workspace_git_writable)`;
    "The `.git` of a writable workspace"), the names of its `secrets` (a name, never a value: `describe` reads
    no credential), its image (`container_image`, else the run's shell image), its `mounts` and its
    `timeout_seconds` when the block sets them. A writable mount that the block writes out and that lies in no
    folder of your `writable_mounts` is followed by `(writable, in no folder of writable_mounts: a run is
    refused)`, and for a block with `workspace_writable: true` by `... a run is refused unless the folder lies in
    its workspace)`; `describe` reads the home's `traceworks.json` for both ("Writable mounts"). An image of the package itself is followed by what its Dockerfile
    says it holds, as in `image ./images/areas — eclipse-temurin:21-jdk-noble: the area finder on JDK 21` (see
    "images"); a child workflow of another package gets no such text, since a run refuses a `./images/` there.
  - `ai:` for a block that calls an AI: the provider, model and effort the call runs with (the block's own, else the
    workflow's; `CLAUDE (the default)` and `the provider's default` when neither names one), its `tools`, its `web`
    mode with the `web_hosts`, `untrusted input` when the step declares it or has web, the workspace when the model
    has tools, and the timeout when set.
  - `also:` for the commands a block runs besides the one shown: a `run_if` test, the `items_command` of a
    `foreach`, the condition of a `while` or `conditional` and the `then_command` and `else_command` of a
    `conditional`. A `run_if`, an `items_command` and a shell condition always run in the run's shell image without
    network.
  - `person:` for an `approval` or `human-input` block (`a person is asked`; with `actor: ai` an AI answers in a
    person's place, and the line says so) and for an `agentic` block with `require_approval: true`.
- **Nested blocks**, indented below their block: the blocks of each case of a `switch` and of its `default`, and the
  workflow a `sub-workflow` block or a `foreach` with `workflow_id` starts, found as a run finds it (a path relative
  to the package, or an id on the operator's `package_path`) and shown with its own blocks, to any depth. A workflow
  of another package directory is marked `of another package`. A reference that cannot be shown is named with the
  reason: `not shown:` a placeholder that is resolved only when the block runs, a workflow that is not found, or the
  workflow the block itself is part of. A case block has no position of its own and gets a `-`. `foreach`, `retry`,
  `while` and `conditional` run commands, not blocks; a `blocks` list in a block's config is shown as `never run`,
  because no block type runs one.
- **The counts**: the blocks shown (those of switch cases and of the child workflows shown included), how many call
  an AI (and how many of those have web), how many shell steps have `network: true`, how many ask a person, and how
  many sub-workflows are not shown and so not counted. An AI step is no "step with network" here, though its
  container reaches its provider: where it can connect is the operator's decision ("The network of an AI step").

`describe` shows the workflow's side only. What the operator's `traceworks.json` decides is not shown: `ai_network`
and `ai_hosts` (where the container of an AI step can connect), `ai_images`, the output limits, and
`allow_host_execution` (whether a step may run on the host at all). The one setting it reads is `package_path`, to
find a sub-workflow by id as a run would. It creates no home and writes nothing there.

It does not run the analyzer: a value the run would refuse is shown as `invalid value` or `not stated`, a package
with `check` errors is described all the same, and only `traceworks check` says what a run would refuse. A package
that cannot be read fails as it does for `check`: exit 2 when there is nothing at the path, exit 6 with the loader's
message when the package cannot be loaded. Text from the package is printed on one line each, with control characters
replaced by `?`.

`traceworks describe <package> --html [<file>]` writes the same as one HTML page and prints its path. Without a
file the page is `<package directory name>.describe.html` in the working directory; a file is relative to the working
directory and is written again when it exists. The page is never put into the package unasked, because every file
of a package is part of its digest: when the working directory lies inside the package, name the file. A file you
name inside the package is written, with a note on standard error that the digest has changed. The page is built by
the renderer of `run.html`, with its style: one self-contained file, no scripts, a Content Security Policy that
blocks every load, all text HTML-escaped, and a character that changes how the page reads shown as its code point.
The file is given after the package or before it (`--html` takes no value of its own, so `--html=<file>` is refused).

### images

`traceworks images build [<package>]` builds the images that steps run in, with `podman` or `docker` (the one
`container_runtime` in `traceworks.json` names, else the first that works), and prints one line per image: `built
<tag>` or `pulled <reference>`. An image that fails is reported on standard error with the runtime's error, and the
other images are built all the same. It exits 0 when every image was built or pulled, else 6 (also when no runtime is
installed). A build runs the `RUN` steps of the package's Dockerfiles, so `images build <package>` refuses a sealed
package whose seal does not hold, for the same reasons and with the same exit code 6 as a run, before it looks for a
container runtime; the refusal is a `REFUSED` line of the seal log with the action `images-build` (see "seal, unseal
and seals"). An unsealed package is built whatever `require_seal` says.

- Without a package it builds the four built-in images, in this order: `shell`, `claude`, `pi` and `egress` (the
  proxy that is the only door of an AI step's container; its source is in the image's directory and is compiled
  when the image is built). Their Dockerfiles are in `images/` of the repository, and the jar carries them. A tag is
  `traceworks/<name>:<h>`.
- With a package it builds the built-in images the package uses: `shell`, which every container run needs, the
  image of each AI provider that its steps, and those of its literal child workflows, use, and `egress` when they
  use any. Then it builds each
  `images/<name>/Dockerfile` of the package, tagged `traceworks-pkg/<package id>/<name>:<h>`, and pulls the registry
  images that the package and its literal child workflows name as literal `container_image` values, each once.
- `<name>` must match `[a-z0-9][a-z0-9._-]*`. A directory under `images/` with a Dockerfile and another name is no
  image: `images build` names it on standard error and exits 6, `check` reports `invalid-image-name`, and a run that
  names it as `./images/<name>` is refused (exit 6) because it "is not a valid package image name".
- `<h>` is the first 12 hex characters of the digest of the image directory, a SHA-256 over each file's path, mode
  (`755` when its owner may execute it, else `644`) and content, as for the package digest. So a changed directory
  gets a new tag, and so does a package image whose files a checkout makes executable. The jar keeps no file modes,
  so the digest of a built-in image counts every file as `644`.

A run never builds and never pulls an image.

**What an image holds.** A name like `./images/areas` says nothing about the container a step runs in, so every
Dockerfile says it in a label, and the record keeps it: `run.started` lists each built-in image and each package
image with the base of its last `FROM` and its `LABEL org.opencontainers.image.description` (docs/events.md).
`run.html` shows the list in its Workflow section and repeats base and description at every container command, and
`show` prints it under `Images`, and `describe` says it after the image of a shell step. Give a package image the label, on one line:

```dockerfile
FROM eclipse-temurin:21-jdk-noble@sha256:...
LABEL org.opencontainers.image.description="the area finder compiled from src/ on JDK 21"
```

Without it, `check` warns `image-without-description`. Changing a Dockerfile changes the image's tag, so build the
images again after adding the label.

### credentials

`traceworks credentials` keeps the entries of `<home>/credentials.json`: the values of secrets ("Secrets") and of the
AI providers' credentials ("AI steps"). It needs no run before it: `set` creates the home and the file.

```bash
traceworks credentials set CLAUDE_CODE_OAUTH_TOKEN    # asks for the value; the terminal does not show it
traceworks credentials set DEPLOY_TOKEN < token.txt   # or takes it from a piped standard input
traceworks credentials list                           # the stored names, never a value
traceworks credentials remove DEPLOY_TOKEN
```

- **The value is never an argument.** An argument stays in the shell's history and shows in the process list. On a
  terminal `set` asks for the value without showing it. From a pipe or a file the whole input is the value, without
  its one final line end, up to 64 KiB.
- **`set`** stores the value under the name and replaces the one the name has; it prints `stored <NAME> in <file>` or
  `replaced <NAME> in <file>`. A name matches `[A-Z_][A-Z0-9_]*`. A value no run would take (see "Secrets": shorter
  than 8 characters, with a quote, and so on) is refused before anything is stored, and the refusal does not show it.
- **Permissions.** A home that does not exist is created for its owner alone (`rwx------`); one that exists keeps
  its permissions. The file is written as a new file that only its owner may read (`rw-------`), which then takes the
  place of the old one in one step, so no reader sees half a file. A file its group or others can read is not
  repaired silently: every command refuses it (exit 6) until you `chmod 600` it, since its values may already have
  been read. A file system without POSIX permissions is refused too.
- **`list`** prints one name per line, sorted, or `no credentials in <file>`.
- **`remove`** removes the name and its value, and exits 6 when the file holds no such name.

A value in the file takes precedence over an environment variable of the same name. A run reads the file when it
starts or resumes. Give `--home` to `credentials` when the run is given one.

### findings

`traceworks findings [<key> [list [--json] | show <finding> | items [--json] | verify]]` reads the registers of the
home (docs/registers.md): the findings that audit runs stated across runs, and the items those audits judge.

```bash
traceworks findings                          # the registers, each with its counts by status
traceworks findings architecture list        # the findings that are not resolved
traceworks findings architecture list --all  # every finding, the resolved ones too
traceworks findings architecture list --status accepted_risk
traceworks findings architecture list --json # the listed findings as a JSON array, for a script
traceworks findings architecture show 9d3692a8   # one finding, by its id or a prefix of 8 characters, with its detail
traceworks findings testing items            # the items the audit judges, each with its state and verdict digest
traceworks findings testing items --json     # the same as a JSON array, for a script
traceworks findings architecture verify      # the chain, blobs, state.json and the anchors in the ledger; exit 8 when broken
traceworks findings architecture render      # writes state.json and report.html again and prints the page's path
traceworks findings architecture accept 9d3692a8 --note "a fold over every event type stays one" --until 2027-03-31
traceworks findings architecture dismiss 9d3692a8 --note "not ours"
traceworks findings architecture reopen 9d3692a8 --note "fix it after all"
traceworks findings architecture dismiss 9d3692a8 --note "not ours" --agent claude-code   # an agent decides under your user
```

A decision is recorded in the register with who decided (the actor, see "Actor", and the agent, below) and when,
and it changes the
finding's status: `accept` makes it `ACCEPTED_RISK` with a review date (`--until`, a date at most 180 days ahead,
which is the default), freezes its evidence for the comparison with later runs, and needs `--note`; `dismiss` makes
it `DISMISSED`, with `--note` and without a review; `reopen` makes an accepted, dismissed or resolved finding `OPEN`
again. A wrong transition (accepting a resolved finding, reopening an open one) or a date out of range is refused
(exit 6) and records nothing; `accept` or `dismiss` without `--note` is a usage error (exit 2). An accepted risk comes up for review (`AWAITING_VERIFICATION`) when
its date has passed, and opens again when a later report shows its evidence got worse. A decision covers the
severity it was made on: an accepted or dismissed finding that a report states with a higher severity is `OPEN`
again (`finding.reopened`, reason `higher_severity: LOW → CRITICAL`), unless `traceworks.json` says
`"decision_on_higher_severity": "keep"`; an equal or lower severity, or other evidence alone, leaves the decision
(docs/registers.md, "What a decision covers"). An accepted or dismissed
finding that three applied reports in a row do not state, or whose rule left `catalog.json`, is resolved. For a
rule that `catalog.json` marks as judged by a model, silence resolves nothing: an open finding stays open until a
report names it as gone, a decided one stays decided, and `show` says how long no report has mentioned an open one
(`Silent    the last 2 applied reports did not mention it`; docs/registers.md, "Reconciliation").

**An agent that decides names itself.** An AI agent that works with your user is your user to the machine, so the
actor alone would record its decision as yours. `accept`, `dismiss` and `reopen` take `--agent NAME`, and without
the option the environment variable `TRACEWORKS_AGENT`, which a harness sets once for its agent; the option wins.
The register records the name beside the actor (`agent` in the line of the decision, `disposition.agent` in
`state.json`, `list --json` and the register folder of a run), and the views say it after the actor:

```
$ traceworks findings security dismiss 9d3692a8 --note "RunPaths refuses this mount before the call" --agent claude-code
dismissed 9d3692a8 through the agent claude-code
$ traceworks findings security list
9d3692a8  DISMISSED  LOW       path_check  core/Mounts.java#Mounts.check(Path)  A link gets past the path check  (through the agent claude-code)
$ traceworks findings security show 9d3692a8
...
Decision  DISMISSED by operator@host through the agent claude-code at 2026-11-17T10:02:11.418Z, made on severity LOW
```

- **The name:** at most 64 characters, each an ASCII letter or digit, one of `. _ - : / @ +`, or a single space
  between two of those; white space around it is dropped.
- **A blank name, or one beyond these bounds, is a usage error** (exit 2) and records nothing, whether it came from
  the option or from the variable: it is not taken for "no agent". `--agent` on any other `findings` command is a
  usage error too; the variable is read by these three only.
- **A decision without an agent** is recorded and shown exactly as before.
- **The name is self-declared and unsigned.** An agent that leaves it out is recorded as you, and anyone can give
  any name. It is a label like the actor, not a proof (docs/registers.md, "Who decided").
- **An earlier version of Traceworks refuses a register in which an agent decided** (`register.requires` with the
  feature `decision_agent`), so that it cannot show such a decision as yours.

The answer to an `approval` or a `human-input` of a run (`traceworks approve`, `traceworks answer`) takes the same
option and reads the same variable ("approve and answer"). There no earlier version refuses the record: it shows
such an answer with its actor alone.

A `list` line is the first 8 characters of the finding's id, its status, severity, rule key, subject and title,
sorted by status (`OPEN`, `AWAITING_VERIFICATION`, `ACCEPTED_RISK`, `DISMISSED`, `RESOLVED`), severity, rule key and
subject; a row whose decision was made on another severity than the finding has now ends with `(decided on LOW)`,
and a row whose decision is no longer in force with `(dismissed on LOW before, not in force)`. `list --json` holds
`disposition.severity` where the two severities differ and `"in_force": false` in the `disposition` of a decision
that is no longer in force.
`show` prints every field: when the finding was first and last seen, how often, its regressions, and the
decision a person took, with the severity it was made on (`Decision  DISMISSED by anna@laptop at ..., made on
severity LOW, not on the CRITICAL the finding has now`), the evidence it was made on when the finding has other
evidence now (`Made on   evidence: ...`), and `not in force: the finding is OPEN` for a decision a report or a
person has opened again. Every command reads the register's log, never its `state.json`; `verify` says when the two
differ. An unknown key is a usage error (exit 2).

Below the fields, `show` prints the finding's detail: what is wrong, the fix and the quoted line, as the run that
last reported the finding wrote them into `findings.jsonl` (docs/registers.md, "The detail of a finding"). The
register does not hold them: they are read from the record of that run in the home.

```
Detail    from findings.jsonl of run 4c1f6a52-0d3e-4b56-9a57-2f1c8f0f3b1e
  item      A012
  file      traceworks-core/src/main/java/.../Mounts.java:41
  quote     if (path.startsWith(home)) {
  severity  high
  the model's words, not checked:
    issue   The check is lexical: a symbolic link gets past it.
    fix     Compare the real path.
```

Every value is made one line of text, with control characters replaced and cut at 200 characters (one too long
for its line of the terminal goes on behind `|+`); the whole text is in the
file (`show <run>` lists it as `file findings.jsonl ... blob <sha256>`). When there is no detail, one line says why
and the rest is shown: `Detail    none: run <id> is not in this home`, `... recorded no findings.jsonl` (a run of
an earlier version, or an audit that writes none), `... has no entry for this rule and subject in its
findings.jsonl`.

`list --json` prints the listed findings (the same selection as without it: `--all`, `--status`) as one JSON array
in ASCII (every other character is a `\u` escape, so no control or format character of a step's text reaches the
terminal, and a JSON reader gets the same strings),
each finding with the fields it has in `state.json`: `id`, `rule_key`, `subject`, `severity`, `title`,
`description`, `evidence`, `tags`, `status`, `first_seen`, `last_seen`, `occurrences`, `regressions`,
`unverified_evidence`, `resolved_at`, `absent_reports` and `disposition`, and `item` when the finding names the item
of the audit it belongs to.

An audit that asks a model about one item per call (a class, an area of the code) can list its items in its report;
the register then knows which of them are settled (docs/registers.md, "Items"). `traceworks findings` adds a line
under such a register, and `findings <key> items` lists the items, what needs a look first:

```
$ traceworks findings
testing  testing  OPEN 14  DISMISSED 6  RESOLVED 71
  items 87 of 110 settled: 71 clean, 16 decided; 20 open, 3 not judged
$ traceworks findings testing items
open        3fa9c2d1e07b4a55  io.example.Reconciler
not judged  -                 io.example.Parser
decided     0be7d4a1c3f29e88  io.example.Writer
clean       91c07be2aa4d0f16  io.example.Log
```

`open`: a finding of the item is open; `not judged`: no run judged it, or it changed since; `decided`: every finding
of it is accepted, dismissed or resolved; `clean`: judged, nothing found. The digest is the one the item had at its
last verdict. `items --json` prints `name`, `state`, `digest`, `verdict_digest` and `verdict` per item, in ASCII
as `list --json`.

Every register has a page, `registers/<key>/report.html`, written after every applied or rejected report and after
every decision: the findings by status with their fields, the history of the log and the verification line, as
self-contained as `run.html`. `findings <key> render` writes it again.

`findings <key> verify` prints two lines and `verified`, or the problems and exit 8:

```
chain     ok (18 events, head 3fa9…)
anchor    ok (seq 17 is the head the ledger holds, 9 anchors)
verified
```

`chain` is the register's log by itself: its hash chain, its blobs, the recorded states and `state.json`. `anchor`
compares the log with the anchors the ledger holds of it, which finds a changed last line and a cut end
(docs/registers.md, "The anchor"). `anchor    none: ...` is a register the ledger has not anchored yet, and `anchor
ok up to seq N; ...` names lines after the last anchor, which are unproven until a write anchors them; neither fails.
`anchor    NOT checked` is a ledger that exists and cannot be read (exit 8). A write that finds lines after the last
anchor takes them, records `register.unanchored` and says so: a decision or a `render` as `traceworks: note: seq N
was not anchored before this write ...` on standard error, a run in its summary line of the register. A decision or a
`render` whose anchor the ledger could not take is kept and prints `traceworks: warning: ... is not anchored in the
ledger (<why>)` on standard error, and a run's summary line of the register ends with `not anchored in the ledger
(<why>)`.

The next run of the audit gets what its register knows, as untrusted platform data: `{{ audit_findings }}` is the
block of open, awaiting, accepted and dismissed findings (never the resolved ones), one row each with status,
severity, rule key, subject, title, evidence and the decision's note (and `Decided on: LOW, not on CRITICAL` where
the decision was made on another severity than the row shows), at most 250 rows or 60 000 bytes, the rest
counted; `{{ previous_report }}` is the text of the last valid report, its envelope cut and every marker defused, at
most 3000 characters; `{{ audit_items }}` is the items of the register, one row each with state, verdict digest and
name, for the step that chooses what to judge. Before the first report they read `(no known findings)`,
`(no previous report)` and `(no known items)`. A shell line refuses them, as every untrusted text; a block hands them to its command under `values` ("check", "Values: how a protected value reaches a command").

The same reaches the steps without those caps as files: `{{ register_dir }}` is a folder of the run with
`findings.jsonl` (every listed finding, with its whole evidence and its description), `resolved.jsonl`,
`items.jsonl` and `previous-report.md` (the whole report). A container mounts it read-only at `/register`; the
container of an AI step does not get it. docs/registers.md, "The register folder of a run", has the formats and
what the run records of the folder.

**Hooks.** With `hooks.register_changed` in `traceworks.json`, the program runs on the host after every audit run
whose report changed the register (something new, resolved, reopened or due for review) or was rejected, with the
diff as JSON on its standard input: `{format, register, workflow_id, run_id, report_valid, new, resolved, reopened,
review_due, still_open, counts}`, each finding with its id, rule key, subject, severity, title, description,
evidence, tags and status. It runs without a shell, with the operator's own environment variables, in the register's
folder, after the register's lock is released; its exit code, duration and the tail of its standard error are
recorded as `hook.ran`, and the summary says `hook ran` or `hook exited N`. A failing hook never changes the run's
exit code. `examples/hooks/todo-service.sh` posts the diff to a TODO service over HTTP; the same shape serves GitHub
Issues or Jira. A workflow never configures a hook.

A run of an audit workflow ends its summary with what it did to the register:

```
  register architecture: 1 new, 3 resolved, 0 reopened, 17 open now
  register security: 0 new, 2 resolved, 0 reopened, 9 open now, 3 of them not mentioned by this report
  register architecture: not applied — the report step 'report' ended FAILED
```

### seal, unseal and seals

A seal fixes a package: once a package is sealed, a run of it is refused when the package changed. Files cannot be
locked, so nothing stops an edit. The run refuses instead.

```
traceworks seal <package> [--key FILE]
traceworks unseal <package> [--note TEXT]
traceworks seals
traceworks seals log
traceworks seals verify <package>
```

**Setting up.** Sealing signs with an SSH key, through `ssh-keygen -Y sign` (OpenSSH 8.1 or later). The key must be
an Ed25519 key. Traceworks verifies signatures itself and reads no other key type, so a seal made with an RSA, an
ECDSA or a hardware (`sk-`) key is refused.

1. Make a key, or take one you have: `ssh-keygen -t ed25519 -f ~/.ssh/traceworks-seal`. A key with a passphrase works
   on a terminal (`ssh-keygen` asks for it) or through the SSH agent.
2. Name it in `traceworks.json` as `signing_key`, or pass `--key FILE` to `seal`. The file is the private key, or
   the public key when the agent holds the private half.
3. Allow it: add one line for the key to `<home>/allowed_signers`, in the format of git and OpenSSH. The first word
   is a name for the signer, then the public key:

   ```
   anna@example.org ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICn6AFfARimOwmTjbauTkhCQ/WWiufbaJ0epa1m8nHr8
   ```

   `traceworks seal` refuses a key the file does not allow, and prints the line to add. Nothing is allowed on first
   use. The option `namespaces="..."` is read, and must then include `traceworks`. A line with any other option
   (`valid-after`, `valid-before`, `cert-authority`) allows nothing, because Traceworks does not read those options
   and will not allow more than the line says.

**`seal`** computes the seal digest of the package, signs it, checks its own signature and the signer as a run will,
appends a `SEALED` line to the seal log of the home (`<home>/seals/events.jsonl`) and writes the seal into the
package as `traceworks.seal`. Sealing a sealed package again replaces its seal. It exits 6 and writes nothing when
it cannot seal: no key, a key that is not allowed, a package without an `id`, or a package a seal cannot cover.

```
sealed Release (5d2f6c1e-...)
  package  /srv/workflows/release
  digest   5f2c9d0e...
  covers   the packages it calls: 9a41c7b2-...
  signer   anna@example.org (key SHA256:0pVQIJF1boObMiCP7RTVl8F5utu6I2ROqm7b7z4L2Ik)
  seal     /srv/workflows/release/traceworks.seal
```

**What a seal covers.** The seal digest covers every regular file of the package directory: `workflow.json`,
scripts, prompts, Dockerfiles, `catalog.json`, each with its path, its content and whether its owner may execute it.
It leaves out the top-level `.git/` and `traceworks.seal` itself. It also covers every other package the workflow
calls: each package in another directory that a `sub-workflow` or `foreach` block names with a literal
`workflow_id`, at any depth, with every file of it and with the seal file it carries, if it has one. So a change in
a called package breaks the seal of the caller, and so does sealing or unsealing a called package afterwards: seal
the called packages first, then the caller. The called packages count by workflow id and digest, not by path, so a
seal holds when the packages are copied to another machine together.

What the digest does not see: an empty directory; a file that is not a regular file (a named pipe, a socket; a
symbolic link refuses the package); permission bits other than the owner's execute bit; the top-level `.git/`,
which a container does not see either.

A seal can only fix what the digest reaches. A package with one of these cannot be sealed, and a sealed package
with one is refused:

- a `workflow_id` with a placeholder (`{{ ... }}`), or a literal one that cannot be loaded;
- a `workflow_id` that names a workflow under the package's top-level `.git/`, which the digest leaves out;
- a `container_image` with a placeholder;
- a `container_image` that names a registry image without a digest: the reference must end with `@sha256:` and 64
  lowercase hex characters;
- a Dockerfile under the package's `images/` that names an image without such a digest, in `FROM`, in
  `COPY --from=` or in `RUN --mount=...,from=`. `scratch` and the name or number of an earlier build stage are fine.

Not covered: the built-in images (`shell`, `claude`, `pi`, `egress`), which belong to the Traceworks version; the
images the operator names in `ai_images`; the bytes of an image built from a package Dockerfile (the Dockerfile is
covered, the build is not); the workspace, and the source of `--source`.

**The seal file.** `traceworks.seal` travels with the package: commit it with the package. It names the workflow
id, the seal digest, who sealed and when, and carries the signature over those four. `signer` is the name the
sealer declared (the actor of the `seal` command). Nobody checks that name. What is checked is the key: its
fingerprint, and the names `allowed_signers` gives it, which `run.started` records as `key` and `principals`. It must be exactly the file
Traceworks writes; one with other spacing or another field is refused, so the file holds nothing the signature does
not cover.

```json
{
  "version": 1,
  "workflow_id": "5d2f6c1e-...",
  "digest": "5f2c9d0e...",
  "signer": "anna@example.org",
  "sealed_at": "2026-11-13T12:00:00.000Z",
  "signature": "-----BEGIN SSH SIGNATURE-----\n...\n-----END SSH SIGNATURE-----\n"
}
```

**What a run does with a seal.** `traceworks run` and `traceworks resume` check the seal before anything else, and
every doubt refuses the run (exit 6). A package is sealed when it carries `traceworks.seal`, or when the seal log of
this home holds its workflow id as sealed. A sealed package runs only when all of this holds:

- the package carries a seal file, it is a seal, and it names this workflow;
- its digest is the seal digest of the package as it is now;
- its signature is a valid one over the seal's fields;
- `allowed_signers` of this home allows the key that signed.

- when the seal log of this home does not hold this seal yet, the seal is newer than the last change the log holds
  for the workflow (see "The seal of another home").

A package the run calls that is sealed itself must hold its own seal in the same way, whether or not the calling
package is sealed: every package reached through a literal `workflow_id` that carries `traceworks.seal`, or whose
workflow id this home holds as sealed. A workflow named through a placeholder is not known before the run and is
not checked.

So these are refused: a file of the package or of a called package changed; the seal file was deleted by hand while
the home holds the workflow as sealed (use `unseal`); the seal was signed by a key this home does not allow; the
allowed signers file is gone or cannot be read; the seal log cannot be read, has a changed or missing line, or holds
a line of a type this version does not know. The message says what to do. `--no-check` and
`--allow-changed-package` change none of it.

A refused start writes no run folder. The refusal is in the seal log as a `REFUSED` line, with the package, the
reason and who asked; `traceworks seals log` shows it. A refusal that repeats the last line of the log (the same
workflow, reason and digest) adds no line, so a job that is refused every minute does not fill the log. When the
seal log itself is what does not hold, nothing is appended to it.

A run of a sealed package records its seal in `run.started`; `show`, `run.html` and the manifest name it, and
`traceworks verify <run>` checks its signature. For any other run, `show` and `run.html` say `unsealed`.

A resume also compares with the start of the run:

- A run that started with a sealed package goes on only while the package is sealed with the same seal digest. When
  the package was unsealed, or sealed again after a change, the resume is refused: restore the package and its seal,
  or cancel the run and start a new one.
- A run that started unsealed goes on when its package was sealed in the meantime and is unchanged. When the package
  is sealed now and differs from the package the run started with, the resume is refused, also with
  `--allow-changed-package`: it would run the definition recorded at the start, which the seal does not cover.

**The seal of another home.** The seal file is the authority, and the allowed signers are what a home trusts. A
package sealed elsewhere runs in a home that allows its signer's key, and is refused in one that does not. The seal
log is the history of this home, and its memory of which workflows are sealed here. The workflow id is the key: a
second copy of a sealed workflow, in another directory, is sealed too, and needs its seal file.

A valid seal that this home's log does not hold is taken when the log never named the workflow, or when the seal's
signed time is later than the last change the log holds for it (its last `SEALED` or `UNSEALED` line, or the signed
time of the seal that line names, whichever is later). The run that is admitted writes the seal into the log as a
`SEALED` line with `adopted: true`, with the seal's own signer and time, so the home knows it from then on. A seal
that is not newer is refused (`seal-superseded`): that is an earlier seal put back, for example the kept
`workflow.json` and `traceworks.seal` of the version before, or the seal of a package that was unsealed here. Seal
the package again to go on.

The comparison uses the time the sealer's machine signed, against the clock of this home. There is no tolerance: a
seal from a machine whose clock runs behind this home's last change is refused, and sealing again is the way on. A
seal from a clock that runs ahead is taken, and its signed time then is the mark later seals must pass. An allowed
signer chooses the time they sign; the rule stops a seal being replayed by someone without a key, not a signer.

**`unseal`** appends an `UNSEALED` line to the seal log and deletes `traceworks.seal`. It is not signed: the log
names who did it, and `--note` says why. With `seal_immutable: true` in `traceworks.json` it is refused. It also
clears a home that holds a workflow as sealed whose seal file is gone.

**`seals`** lists the workflows this home holds as sealed: the id, the start of the seal digest, who sealed it,
when, and where the package was. **`seals log`** prints every line of the seal log. Both check the chain of the log
first and exit 8 when it does not hold. **`seals verify <package>`** says what a run of the package would find, and
records nothing: `sealed`, `unsealed`, or why the seal does not hold (exit 8).

**Requiring seals.** `require_seal` in `traceworks.json` says which runs need a sealed package:

| Value | Meaning |
|---|---|
| `"never"` | the default: an unsealed package runs |
| `"unattended"` | an unsealed package is refused when Traceworks has no terminal, unless the command line carries `--allow-unsealed`. A terminal counts also with `--no-input`. This guards against an accident, such as a scheduled job that picks up an unsealed package. It does not stop a caller, who can pass the flag or give the command a terminal |
| `"always"` | an unsealed package is refused; `--allow-unsealed` changes nothing |

A sealed package whose seal does not hold is refused under every value. Only `"always"` makes sure that nothing
unsealed runs: with write access to a package alone, deleting its seal file and changing the `id` in its
`workflow.json` gives a package this home has never heard of, which is unsealed under the other two values. docs/security.md, "Seals", says what a
seal protects and what it does not.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | the run completed, or the command succeeded |
| 1 | the run completed with errors: a `checked_command` step failed, and the run went on (see "When a step fails") |
| 2 | usage error, or an unknown or ambiguous run or package, or a run prefix shorter than four characters |
| 3 | the run failed |
| 4 | the run was cancelled |
| 5 | the run is parked: it waits for an answer, or it was paused (`traceworks pause`, Ctrl-C) and waits for its `resume` |
| 6 | refused: the start check failed (no container runtime for a container run, a missing image, a workspace or package that cannot be mounted, a secret without a value or with a value shorter than 8 characters, a `credentials.json` others can read), `credentials` cannot trust or write `credentials.json`, was given a value no run would take or none at all, or shall remove a name that is not stored, a host run's resume lacks the consent, the workflow was not admitted, the package is invalid, the ledger cannot take an entry (no git, or `ledger/` cannot be created) or could not note the start of the run (the run then ended `FAILED` without a step), the ledger's lock is held for more than 60 seconds, the ledger's own git configuration holds a key that is not allowed there or its `.git` is not a plain directory (docs/events.md, "The ledger's own git settings"), a resume whose run has no start entry and cannot get one, a `verify --acknowledge` of a run the check does not report, the `--source` is not allowed or cannot be checked out, a package or a local source lies in `<home>/workspaces/`, a resume's missing checkout cannot be cloned again, a resume's checkpoint state or a text it refers to is deleted, a resume's checkpoint holds more output text than the output budget in force, a `resume` of a run that has finished (unless it only writes a missing manifest or ledger entry), of a run parked at a question that has no answer yet, of a run that another process writes, or of a run whose package changed without `--allow-changed-package`, a `cancel` of a run that has finished, a `pause` of a run that no process runs or that is already asked to pause, `traceworks.json` holds an unknown key or an invalid value, `check`, or the check before a `run`, found errors, or a seal refused: a `run` or a `resume` of a sealed package whose seal does not hold, of an unsealed package where `require_seal` asks for a seal, a `seal` that cannot sign or whose key the home does not allow, or an `unseal` under `seal_immutable` (see "seal, unseal and seals") |
| 7 | the run completed, but its gate reached the `--fail-on-gate` level |
| 8 | verification failed, or the record of the run does not hold, so the command refused to act on it; the message points to `traceworks verify`, or `findings <key> verify` found a register that does not hold, or `seals` found a seal log that does not hold, or `seals verify` a seal that does not |
| 9 | the run was interrupted (`show` and `watch`): its process ended without the run's end, and `resume` continues it |
| 10 | request queued, not yet recorded: `approve`, `answer`, `pause` or `cancel` put a request into a running run's inbox, and the run's process did not record it within the wait (10 seconds, 30 for `cancel`); the request stays in the inbox, and the run may still carry it out or refuse it |
| 11 | `diff`: the two runs were read and verified, and run B did not reproduce run A (see "diff" for what counts) |
| 70 | internal error |

`traceworks cancel` exits 0 when the run ends cancelled, not 4. `show` of a run that is still going exits 0.

## When a step fails

A block's `on_failure` says what the failure of its step does to the run:

| `on_failure` | When the step fails |
|---|---|
| `stop` (the default) | the run ends `FAILED` (exit 3), with the step's error |
| `continue` | the run goes on with the next block |

A step that the run's output budget fails ends the run whatever its `on_failure` says (see "Output budget"), and so
does a step whose block has a setting of the wrong type (see "check"). A block whose executor fails that way ends
`FAILED` in the record, with the error as its output. After the run's cancel, such a failure ends the step and the
run `CANCELLED` (exit 4), without an error, unless the record could not be written. The step's output is then
`Cancelled: the run was cancelled while this step ran`, and when a JVM error (`java.lang.Error`) ended it, a second
line `Ended by <class name>: <message>` (see docs/events.md, `step.finished`).

A step the run's cancel ended is no failure: it ends `CANCELLED`, and the run ends `CANCELLED` (exit 4) after it,
also with `on_failure: continue` (see "cancel"). The same holds inside a `switch` case, a `foreach` item, a `while`
iteration and a `retry` attempt: a cancelled one counts towards no failure, `max_consecutive_failures` and
`continue_on_error` do not apply to it, and a cancelled attempt is not retried.

Only `continue` goes on (in any letter case, also with spaces around it). Any other value (`fail`, a typo, a
placeholder, a value that is no string) stops like `stop`, so a mistyped setting never turns a failure into a pass;
`traceworks check` takes `continue` and `stop` as the run reads them (in any letter case, with spaces around them) and
reports every other value as the error `invalid-config-value`. The key belongs in the block's `config`: next to it, it has no effect,
and `check` reports the error `unknown-block-field`. In a `switch` case, a block's `on_failure` says whether the case
goes on with its next block; a `switch` step one of whose blocks failed fails, and its own `on_failure` decides for
the run.

What fails a block that holds a command or blocks:

| Block | A failure inside it |
|---|---|
| `switch` | a failed case block fails the step; the case's next blocks do not run unless that block says `on_failure: continue` |
| `foreach` | a failed item does not stop the loop: the other items run, and the step fails after the last one, naming the failed items. `continue_on_error: true` completes the step all the same. After `max_consecutive_failures` failed items in a row (3 by default) the loop stops and the step fails |
| `while` | a failed iteration ends the loop and fails the step, with the output of the iterations so far |
| `retry` | a failed attempt is tried again, one that timed out included, until one completes; when none does, the step fails with the output of the last attempt |
| `conditional` | a failed branch fails the step |
| `sub-workflow`, a `foreach` item with `workflow_id` | the child run ends as a run of its own does (`FAILED` at its first failed step without `on_failure: continue`), and a child that ended `FAILED` fails the step or the item, with the child's last output and its error |

**A child run's gate and its errors stay in the child.** A workflow whose checks live in child workflows ends
`COMPLETED`, exit 0, with no gate, while a child of it is RED or ended `COMPLETED_WITH_ERRORS`: `--fail-on-gate`
does not fire, and a caller that reads the exit code or the gate sees a pass. The child's `gate_status` marker and
the RED of its failed `checked_command` stand in the record under the child's run id (`gate.reported`, and the
`gate` of its `run.finished`; `show` names the gate on the child run's line), and the step that ran the child
copies the child's output, marker included: a copy sets no gate, and a child that completed with errors leaves
that step `COMPLETED`. Only a child that ended `FAILED` fails the step.

A parent carries a child's verdict up with a step of its own after the child, which prints the marker lines it
finds in the child's output (the output of the child's last step, so let that step state the verdict):

```json
{"type": "shell", "name": "carry_gate", "config": {
  "values": {"CHILD": "{{ block.checks.output }}"},
  "command": "printf '%s\\n' \"$CHILD\" | grep -E '^gate_(status|category|description):' || true"}}
```

That step's output is its own, so its marker sets the run's gate. With `checked_command: true` and a command that
ends non-zero for a RED child (`... ; ! printf '%s\\n' \"$CHILD\" | grep -q '^gate_status: RED'`), the run also ends
`COMPLETED_WITH_ERRORS`, exit 1. For an audit whose `report_block` is itself a step that runs a child, `check` says
so (`report-from-child-sets-no-gate`).

A step that is refused before it runs anything ends its run whatever its `on_failure` says, like a setting of the
wrong type: a shell line, an entry of `mounts` or a tool's `container_args` that names a protected value
("Values: how a protected value reaches a command"; `check` reports them before the run where the workflow shows
them, as `protected-value-in-shell` and `protected-value-outside-shell`). In a child workflow such a refusal ends the child run, and the
`on_failure` of the block that started the child decides for the run above it.

A run that reaches its end completes:

- `COMPLETED_WITH_ERRORS` (exit 1) when a `shell` step of the run itself with `checked_command: true` failed. Only
  `on_failure: continue` on that step lets the run go past it. A child run that ends so leaves the step that ran it
  `COMPLETED`;
- `COMPLETED` (exit 0) otherwise, also when a step of another kind failed and `on_failure: continue` let the run go
  on. That step is `FAILED` in the record and in `show`, but the run's status does not say so.

A gate never changes the status; `--fail-on-gate` makes a gate exit 7.

## Interactive and parked runs

A run is interactive when the console is a terminal and `--no-input` is not given. A question is then asked on
the terminal and the run waits for the answer.

Without a terminal (CI, a pipe), or with `--no-input`, nobody can answer inside the process. A top-level
`approval` or `human-input` step that a person answers alone then parks the run:

1. The question is recorded and the step is not finished.
2. The run ends with the status `PARKED` and the event `run.parked`. The process exits with code 5.
3. It prints the commands to go on:

   ```
   traceworks approve '56e7c2a8-...' --answer 'Yes'
   traceworks resume '56e7c2a8-...'
   ```

   An option of an approval and the name of a form's field are text of the workflow, as the question is. A command
   is written out with one only where it stays on one line of a terminal of 80 columns and holds no two blanks in a
   row: an option padded to the terminal's width would otherwise end on a line of its own and read as a hint of
   Traceworks. With the run's id that is an option of up to 9 characters. For anything longer the command is
   printed once with a placeholder (`--answer '<option>'`, `--field '<name>=<value>'`) and the options or names
   follow on lines marked with `|` (`| ` before each, `|+` where one goes on), as the lines of the question are; type the one you choose, quoted for your
   shell, or copy the command from `run.html`. The hint of a question that does not park the run
   (`waiting for an answer: ...`) follows the same rule, and so does every other place that shows options or a
   field's label on the terminal: the question asked at the console (`answer with one of:`, `one of:`,
   `is not one of:`) and the live line of a question (`question: ... [Yes / No]`, or `[options:]` with the
   options on marked lines below it).

   **Text from outside on the terminal.** The same holds for every text that is not Traceworks' own, in every
   command: a question, the hint of a default, a pattern, the name of a workflow or a step, a command line, an
   output, an error, a finding, and the value a message quotes. Such a text is written into its line only where
   it stays on one line of a terminal of 80 columns, has something of Traceworks in front of it there, and holds
   no two blanks in a row.

   - **A text that does not fit** goes on below, on lines that begin with `|+` behind the indent of their line. It
     is broken at a blank; where none fits, behind a separator of a path or a member (`/` or `#` first, then `,`
     or `(`, then `.`); and inside a word only where nothing else fits. What follows `|+` follows the end of the
     line above as it is: `|+ word` after a break at a blank, `|+word` after any other break (behind a separator,
     or inside a word), so nobody reads a blank into a path. Take every line break with the blanks and the `|+`
     behind it out, and the line is as it was. A text that fits a line of its own is not cut in two, and on a
     line that goes on already each further text that does not fit starts a line of its own: so a row of a list
     (`findings <key> list`, `runs --long`, `seals`) keeps its own columns, and a subject or a title too long for
     it stands under the row, each from the start of a `|+` line.
   - **A text that begins a line** has `| ` in front of it, in the place of the two blanks before it where the line
     is indented, so that the columns of a table stay: a workflow's description and what a block runs in `show`,
     the name of a step or an input in `diff`, a problem `verify` lists, a workflow's id in `seals`.
   - **A text with a run of blanks** is written on marked lines only.
   - **A text of several lines** (the question of a parked run, an output under `show --outputs`, a line diff)
     has `| ` before each of its lines, and a line of it that is too wide goes on behind `|+`.

   ```
   76b8fad3  OPEN       MEDIUM    path_check
     |+traceworks-adapters/src/main/java/traceworks/adapters/container/
     |+MountGuard.java#MountGuard.normalized(String), MountGuard.real(Path),
     |+ MountGuard.expandHome(String, Path)
   12:34:56 run started: Security self-audit (run 1ce9f3bf) by you in containers (docker 29.1.3)
   traceworks: The run is refused: image ./images/tool is missing: run 'traceworks images build /home/you/pkg'
   ```

   **What is Traceworks' own is printed whole**, at any length, and your terminal breaks it where it is wider:
   its words, a command or a line of `traceworks.json` to copy, a run id, a number, a digest. A message of
   Traceworks says which of its parts quote a value from outside, and only those parts are kept as above; the
   parts are kept beside the message and are never in its text, so a record, an event, a register, `--json` and
   `run.html` hold the message as plain text, and no quoted value can pass for Traceworks' words. What you typed
   on the command line (a package, a run, an option's value) is shown whole too, unless it holds a run of blanks.
   A value of a field that Traceworks writes into a record itself (a status, an exit code, the name and version
   of the container runtime, a model's id in `runs --long`) is its own where it is one word of ASCII letters,
   digits and `._+~-` of at most 40 characters; anything else in such a field is a text from outside.

   A message that does not say its parts yet is a text from outside as a whole, and broken at 80 columns as one,
   also on a wider terminal: put a command in it together again before you copy it. That holds for the findings
   of `check`, the reasons a child workflow, a mount or a secret's name is refused for, what a damaged record or
   a seal is refused for, the notes and warnings of a run, a failure of `images build`, and the refusal of an
   answer. The lines of `describe` are laid out by Traceworks with the package's names in them, and only their
   length is judged.

   What you named yourself stays whole: the folder of a run in `record` and `report`, and the commands that
   answer and resume a run. At the console a question of one line
   that fits stays behind `? `; any other is printed below a lone `?`, every line behind `| `. The hint of a
   default that does not fit its line goes on the same way, and an empty line does not take such a default.
   Traceworks does not ask the terminal for its width: on one narrower than 80 columns any line can break.
4. `traceworks approve` records the answer. `traceworks resume` (a host run with `--allow-host-execution`, or the
   setting) continues from the last checkpoint. The step asks again and receives the recorded answer at once. For a
   host run, the commands that `run`, `approve`, `answer` and `show` print to go on carry `--allow-host-execution`
   unless `allow_host_execution` in `traceworks.json` gives the consent. `run.html` does not know that setting: its
   resume command carries the flag for every host run.

Not every question parks. These wait inside the process instead:

- a question answered by an AI actor (`actor: shadow`, or `actor: ai`);
- a question asked inside another block (for example the plan approval of an `agentic` step);
- a question in a sub-workflow child.

If the question text differs from the one that parked (for example because it embeds `run_timestamp`,
`started_at` or `trigger_source`, which a resume sets again), the step fails with "the question changed while
the run was parked". Do not put those variables into a question.

`traceworks check` warns about both: `nested-wait-point` for a question that never parks (the plan approval of an
`agentic` step, or a question in a child workflow), and `question-changes-on-resume` for a question that embeds one
of those variables.

## The inbox

Only the process that holds a run's writer lock appends to `events.jsonl`. When a run is running in another
process, `approve`, `answer`, `pause` and `cancel` do not write to the log. They drop a request file into the run's
`inbox/` folder:

```
inbox/1730654400123-6efd92f3-5b4e-4d81-a5da-62e8db978c3a.json
```

A request holds `id`, `kind` (`approval`, `human-input`, `pause` or `cancel`), `run`, `step`, `question_seq`, `answer`,
`values`, `comment`, `actor` and `at`. `step` and `question_seq` bind an answer to the question it was meant for.

The running process looks into the inbox every 200 ms, carries the request out and records the result:
`approval.answered` (with the request id), `request.refused`, `run.pause_requested` or `run.cancel_requested`. The
command waits up to 10 seconds for that record (`cancel` waits up to 30 seconds for the run to end) and prints it. When nothing is
recorded within the wait, the command exits 10: the request is queued, not yet recorded, and `traceworks show`
tells later what became of it. Handled requests move to `inbox/done/`. A request file is at most 8 MiB: a larger
answer is refused by the command that would post it, and a larger file found in the inbox is not read: it moves to
`inbox/done/` with `.invalid` at its name, as a file that is no request does.

A request still in the inbox when a `resume` or a `cancel` takes over a run nobody ran is handled by its kind. An
answer was bound to a question of a run that went on without it: it is refused with `request.refused` and the reason
`the run was not running when the request arrived`. A pause is refused the same way: the run has stopped already. A
cancel is meant for whichever process runs the run next: it ends the run `CANCELLED` (see "cancel").

Exactly one answer counts. If two shells answer at once, or you approve twice on a parked run, the other gets a
clear refusal and nothing is recorded twice.

## Home

The home holds everything Traceworks writes, unless `traceworks.json` names another folder for the runs, the ledger
or the registers ("Places of the runs, the ledger and the registers"). It is, in this order:

1. `--home DIR`
2. the environment variable `TRACEWORKS_HOME`
3. `~/.traceworks`

```
<home>/
  traceworks.json        operator configuration (optional)
  credentials.json       values of secrets and AI credentials (optional, only its owner may read it; written by
                         'traceworks credentials', see "Secrets")
  runs/<yyyy-mm-dd>/<run-id>/    one folder per run, see docs/events.md (or in runs_dir)
  ledger/                a git repository with one commit per started run, one per finished run and one per write
                         of a register (or ledger_dir)
  registers/<key>/       one folder per register (docs/registers.md): events.jsonl, blobs/, state.json, report.html
                         and register.lock, written by the audit runs of that key and by 'traceworks findings'
                         (or in registers_dir)
  ledger.lock            serialises writes to the ledger (with ledger_dir it lies in the ledger folder instead)
  seals/                 the seal log of the home: events.jsonl and seals.lock, written by 'traceworks seal' and
                         'unseal', and by a run or a resume that a seal refused (see "seal, unseal and seals")
  allowed_signers        the keys whose seals this home accepts (optional; written by the operator)
  cache/tools/           reserved for tool runs
  workspaces/<run-id>/   the checkout of a run started with --source (see "Isolated workspace")
```

### traceworks.json

All keys are optional. An unknown key or an invalid value (of the wrong type, or outside its range) is an error that
names the key, and every command that reads the home refuses with it (exit 6).

| Key | Type | Meaning |
|---|---|---|
| `allow_host_execution` | boolean | allow a host run where no container runtime works; default `false` (see "Host execution") |
| `actor` | string | who runs and answers; see "Actor" |
| `package_path` | list of directories | where `workflow_id` looks for workflows by id; a relative directory is relative to the home |
| `runs_dir` | directory or `null` | where the run folders lie, `<runs_dir>/<yyyy-mm-dd>/<run-id>/`; `null` or missing (the default) is `<home>/runs`. See "Places of the runs, the ledger and the registers" |
| `ledger_dir` | directory or `null` | the ledger, a git repository; `null` or missing (the default) is `<home>/ledger`. Its lock file lies in it: `<ledger_dir>/ledger.lock`, which is never committed |
| `registers_dir` | directory or `null` | where the registers lie, `<registers_dir>/<key>/`; `null` or missing (the default) is `<home>/registers` |
| `container_runtime` | `"podman"`, `"docker"` or `null` | the container runtime; `null` (the default) takes the first of `docker` and `podman` on `PATH` that answers `version`. Docker comes first because this release is tested with it (29.1.3); Podman is supported by the code but untested |
| `hooks` | object or `null` | the operator's hooks: `register_changed`, the program and its arguments as a list (never a shell line) that gets the diff of a register after an audit run on its standard input, and `timeout_seconds` (default 60), after which the hook is ended. See "Hooks" and `examples/hooks/` |
| `secret_names` | list of names or `null` | the secrets a run may take from the environment of Traceworks. `null` or missing (the default) and `[]` allow none; a list allows those names. Values from `credentials.json` and the AI providers' credentials are not affected. Each name must match `[A-Z_][A-Z0-9_]*`, else the file is refused (see "Secrets") |
| `writable_mounts` | list of directories or `null` | the folders in or below which a package may mount a host path writable (`:rw` in `mounts`). `null` or missing (the default) and `[]` allow none: every writable mount is refused. Each entry is an absolute path, or starts with `~/` for a folder below your home directory; `/`, your home directory, a folder that holds it and a relative path are refused. No workflow can set it (see "Writable mounts") |
| `workspace_git_writable` | boolean or `null` | whether a container step that may write its workspace (`workspace_writable: true`) may write the workspace's own top-level `.git` too. `false`, `null` or missing (the default): the container gets that `.git` read-only as a whole, so a step reads the repository and cannot commit. `true`: the `.git` is writable like the rest of the workspace, in every run of this home. The checkout of `run --source` is writable with its `.git` either way. No workflow can set it (see "The `.git` of a writable workspace") |
| `container_limits` | object | the limits of every container: `memory` (a string such as `"4g"`), `pids` (an integer) and `cpus` (a string or a number, such as `"2"` or `0.5`); a missing one keeps its default, `"4g"`, `1024` and `"2"` |
| `output_limit_mib` | integer or `null` | how much Traceworks keeps of each stream of every command, in MiB: from `1` to `1024`; `null` (the default) is `16`. A string such as `"16"` or a number such as `1.5` is refused. No workflow can set it (see "Output limit") |
| `run_output_budget_mib` | integer or `null` | how much memory the output text a run holds may take, in MiB: from `1` to `65536`, and at least twice `output_limit_mib`; `null` (the default) is `128`, or eight times `output_limit_mib` when that is more. A smaller value, a string or a fraction is refused. No workflow can set it (see "Output budget") |
| `ai_images` | object or `null` | the image each AI CLI runs in instead of its built-in one: `{"claude": "<image>", "pi": "<image>"}`. A key is a CLI Traceworks runs (`claude`, `pi`); any other, `codex` too, is refused. A value is an image reference: not blank, with no whitespace or control character, not starting with `-` and not a package image (`./images/...`). Missing, `null` and `{}` keep the built-in images. No workflow or environment variable can set it (see "AI images") |
| `ai_network` | `"restricted"`, `"open"` or `null` | where the container of an AI call can connect. `"restricted"` (the default, also for `null`): only to the hosts of its provider and those in `ai_hosts`, through the egress proxy, with every connection in the record. `"open"`: the runtime's default network, any host, nothing recorded. Any other value is refused. No workflow or environment variable can set it (see "The network of an AI step") |
| `decision_on_higher_severity` | `"reopen"`, `"keep"` or `null` | what a register does with a finding a person accepted or dismissed when a report states it with a severity above the one the decision was made on. `"reopen"` (the default, also for `null`): the finding is `OPEN` again. `"keep"`: the decision stays, and the register shows the severity now and the severity decided on. Any other value is refused. No workflow or package can set it: a decision is the operator's (docs/registers.md, "What a decision covers") |
| `require_seal` | `"never"`, `"unattended"`, `"always"` or `null` | which runs need a sealed package; `null` (the default) is `"never"`. Any other value is refused (see "seal, unseal and seals") |
| `seal_immutable` | boolean | `true` switches `traceworks unseal` off; default `false` |
| `signing_key` | path or `null` | the SSH key `traceworks seal` signs with, for `ssh-keygen -Y sign -f`: a private key, or a public key whose private half the SSH agent holds. A relative path is relative to the home. Without it, `seal` needs `--key` |
| `allowed_signers` | path or `null` | the allowed signers file; a relative path is relative to the home. `null` (the default) is `allowed_signers` in the home. No workspace, package or step mount may be this file or a folder that holds it, nor the `signing_key` (exit 6, the refusal names the setting; docs/security.md): keep each in a folder of its own |
| `allowed_models` | list of objects or `null` | the models that may run in this home: `[{"provider": "CLAUDE", "model": "claude-fable-*"}, {"provider": "PI"}]`. `provider` is `CLAUDE` or `PI` (any case); `model` is a model name in which `*` stands for any text, and an entry without `model` allows every model of the provider, its default included. Missing or `null` (the default) is no restriction; `[]` allows no model. A run with an AI step whose provider and model no entry allows is refused before it starts (exit 6), whatever the package names or the caller picks. No workflow or environment variable can set it (see "Model choices") |
| `ai_hosts` | object or `null` | more hosts for the containers of an AI CLI: `{"claude": ["<host>:<port>", ...], "pi": [...]}`. A key is a CLI Traceworks runs (`claude`, `pi`); any other is refused. A host is a name or an IPv4 address (`[a-z0-9]([a-z0-9.-]*[a-z0-9])?`, compared in lower case) with a port from 1 to 65535: no wildcard, no scheme, no path. Missing, `null` and `{}` add none. No workflow or environment variable can set it |
| `local_models` | object or `null` | the model servers of your own that a `PI` step may name: `{"<backend>": {"url": "http://llm.internal:8080/v1", "api": "openai-completions", "models": ["<model>", ...], "secret": "<NAME>"}}`. A step with the model `<backend>/<model>` runs against that server, needs no credential of a provider and reaches nothing but the server. Every value is checked when the file is read, and a wrong one refuses the command (exit 6). Missing, `null` and `{}` declare none. No workflow, workspace, run input or environment variable can set it (see "Local models") |

```json
{
  "actor": "anna@example.org",
  "package_path": ["/srv/workflows"],
  "container_runtime": "podman",
  "container_limits": {"memory": "8g", "pids": 2048, "cpus": "4"},
  "output_limit_mib": 64,
  "ai_images": {"claude": "registry.example.org/ops/claude:2.1"}
}
```

A `workflow_id` in a `sub-workflow` or `foreach` block is either a path relative to the current package (it
starts with `.` or contains `/`) or the `id` of a workflow in an immediate subdirectory of the package path. A
path may be absolute or use `..`, so it can point anywhere on the machine. Run only packages you trust.

### Places of the runs, the ledger and the registers

`runs_dir`, `ledger_dir` and `registers_dir` put the runs, the ledger and the registers in a folder of your choice:
on another disk, or beside a project. Without them nothing changes: each lies in its folder of the home.

```json
{
  "runs_dir": "/data/traceworks/runs",
  "ledger_dir": "/audit/traceworks-ledger",
  "registers_dir": "registers-of-the-team"
}
```

- **The value** is a string. An absolute path is taken as it is. A relative path counts from the home, as in
  `package_path`: the third line above is `<home>/registers-of-the-team`. `.` and `..` are applied. A `~` is not
  expanded, because no shell reads the file: a value that starts with `~` is refused. So is a value that begins
  or ends with white space.
- **What stays in the home:** `traceworks.json` and `credentials.json`, always. So the file is read before the
  places are known, and no setting can move it. The checkouts (`workspaces/`), the caches (`cache/`), the seal log
  (`seals/`) and the allowed signers (`allowed_signers`, unless the setting of that name puts the file elsewhere)
  stay in the home too: a run or a resume that a seal refused is recorded in `<home>/seals`, wherever the runs are.
- **A folder that does not exist** is created when something is first written to it, as the home is: the runs
  folder with the first run, the ledger before the first run starts, the registers folder with the first audit run.
  A command that only reads creates nothing. So a mistyped path starts an empty place; `traceworks runs` then
  lists nothing.
- **A folder that cannot be written:** a run is refused before it starts (exit 6) when its record or the ledger
  cannot be created. A register is written after the run; when that fails the run keeps its status, and its
  summary says `register <key>: not applied` with the reason.
- **Refused, with the key** (exit 6, for every command that reads the home): a value that is no string or is blank;
  a place that exists and is not a directory; a place that is a symbolic link whose target does not exist, or lies
  behind such a link (whoever creates the target would choose the place); a place that is the home or holds it, or
  has the name of a file of the home (`traceworks.json`, `credentials.json`, `ledger.lock`, `allowed_signers`); a
  place that is, lies in or holds `<home>/workspaces`, `<home>/cache` or `<home>/seals`; two of the three in one
  folder, or one inside another. For the last rule the folder of the home that a place would have without its setting counts as well, since earlier
  content may still lie there: `"ledger_dir": "runs"` is refused, and so is `"runs_dir": "runs/2026"`. A `runs_dir`
  whose path holds a comma, a quote or a line break is refused too: every container mounts its run's scratch
  directory from the runs folder, and a mount cannot carry such a path. These paths are compared as written, with
  no link followed.
- **The ledger's lock** of a ledger you placed is `ledger.lock` in the ledger folder, so the folder itself is all
  that must be writable: its parent can be a mount point. The file is not part of the ledger; no commit adds it.
  Without `ledger_dir` the lock stays `<home>/ledger.lock`.
- **Containers do not see them.** In a container run, a workspace, a package or a step's mount that is one of the
  places, lies in one or holds one is refused, wherever the places are and on the real path ("Host execution",
  docs/security.md). A host run has no such wall around anything ("Host execution"). So a
  runs folder beside a project is fine, and one inside the project's working directory refuses every run that works
  there: put it next to the project, not into it. The folders of the home that the settings replace (`runs/`,
  `ledger/`, `registers/`) stay refused as well. And no package, local `--source` or child workflow is read from a
  runs folder, the one `runs_dir` names or `<home>/runs` ("Isolated workspace").
- **Changing a setting moves nothing.** Traceworks reads and writes the folder the setting names now. Move the
  folder yourself while no run of the home is running, for example `mv <home>/runs /data/traceworks/runs`, and then
  set `runs_dir`. A parked run can be moved with the others and resumed afterwards. A run is named by its id
  everywhere, in the ledger (`runs/<run-id>.json`) and in a register, never by its path, so a moved folder is the
  same runs, ledger or registers: `show`, `resume`, `verify` and `findings <key> show` work as before.
- **What is left behind is not part of the home any more.** A run in the old runs folder is not listed and not
  found by its id; `traceworks show <path>` and `traceworks verify <path>` read it as a copied folder, with the
  ledger `not checked`. A ledger left behind is not asked: `traceworks verify <run>` then reports the missing entry
  and exits 8, and the next run starts a new, empty ledger in the new place. A register left behind is not listed,
  and the next audit run starts a new one. Nothing is merged; move the folders before the next run.
- **A parked run left behind** can still be answered and resumed by its path (`traceworks resume <path>`), as any
  copied run folder can ("resume"): Traceworks cannot tell a folder you left behind from one you copied here. The
  run then finishes where it lies, its entry goes to the ledger the home has now, and an audit run feeds the
  register the home has now. `traceworks verify <path>` still reads the folder as a copy and does not check the
  ledger, and `traceworks show <id>` does not find it. Move the folder into the runs folder and all of this is
  consistent again; that is the better order: move first, resume then.
- **One home per folder.** Two homes that name the same folder are not supported and not tested. Two homes that
  name one ledger with `ledger_dir` take the same lock, the one in the ledger folder, so they write it in turn. A
  home that uses a ledger without the setting takes `<home>/ledger.lock` instead: do not name the fixed
  `<home>/ledger` of one home as the `ledger_dir` of another, since the two would not exclude each other. A shared
  runs or registers folder would mix the runs and the audits of both.

### Writable mounts

A block may ask for more mounts than the run's own (`mounts`, each `host:container:ro` or `host:container:rw`). A
read-only one is checked as before. A writable one is refused unless you listed a folder that holds it:

```json
{
  "writable_mounts": ["~/traceworks-out", "/srv/reports"]
}
```

- **What an entry is.** A directory: an absolute path, or one that starts with `~/`, which is your home directory as
  on the host side of a mount. It need not exist. The file is refused (exit 6, by every command that reads the home)
  for an entry that is no string, begins or ends with white space, is not absolute, is `/`, or is your home
  directory or a folder that holds it, as written or behind a symbolic link.
- **What it allows.** A writable mount whose real path (`..` and symbolic links resolved) is a listed folder or lies
  below one, with the listed folder resolved the same way: a link inside a listed folder that leads out of it allows
  nothing. Without the setting, and with `[]`, no package mounts a host path of yours writable.
- **What it does not allow.** Anything the mount check refuses anyway (docs/workflow-format.md, the row on declared
  mounts): a login, a folder with a credential store of its own, a socket, the Traceworks home, the package, the
  workspace of a step without `workspace_writable: true`, the `.git` of a writable workspace (unless you opened it:
  "The `.git` of a writable workspace"). A file directly in
  your home directory (`~/.bashrc`) can never be mounted writable, since its folder cannot be listed.
- **What needs no entry.** What the step writes anyway: the run's scratch directory (`{{ scratch_dir }}`), the
  cache folder (`cache_dir`) and the output folder (`output_dir`) of a tool step, and the workspace or a folder in
  it while the step has `workspace_writable: true`, your own workspace as well as the checkout of `run --source`:
  the step writes it at `/workspace` anyway, and a second, writable mount of a folder in it shows the step nothing
  more. That is judged by the real path, so a link in the workspace that leads out of it needs an entry for where
  it leads, and so does a folder that holds the workspace. `describe` knows no workspace: it marks such a mount with
  `a run is refused unless the folder lies in its workspace`.
- **When it is refused.** A mount that the workflow writes out (`"~/out:/out:rw"`) is refused by the start check,
  before the run is created and before a resume records anything (exit 6), with every such mount in one message. A
  mount that a placeholder names (`"{{ out_dir }}:/out:rw"`) is known when its step runs: the step is refused with
  exit code 125, and nothing starts. Both name the mount and the line to add:

```
The run is refused: Block 2 "export": the mount ~/out:/out:rw is refused: a package may mount a host path writable
only in or below a folder that writable_mounts in /home/anna/.traceworks/traceworks.json lists, and /home/anna/out
lies in none; mount it :ro, or, if every package you run may write that folder, allow it with this line in
/home/anna/.traceworks/traceworks.json: "writable_mounts": ["/home/anna/out"]
```

The list is a grant to every package you run in this home, not to one. List folders that hold results, and none
that holds what the host runs later: a repository (its `.git/hooks`), a folder on your `PATH`, a folder of startup
files (docs/security.md, "A listed folder is open to every package"). `traceworks describe <package>` shows the
mounts of every block, and marks a writable one that no listed folder holds.

### The `.git` of a writable workspace

A step with `workspace_writable: true` writes the workspace. In a container it does not write the workspace's own
top-level `.git`: the container gets that `.git`, a directory or the file of a linked worktree, read-only as a
whole. The step reads the repository (`git log`, `git diff`, `git status`) and can leave nothing in it that your
next git command there would run on the host: no hook, no config, no `commondir`. So it cannot commit, stage or
stash either. You open it with one setting:

```json
{
  "workspace_git_writable": true
}
```

- **What it allows.** In every run of this home, a container step with `workspace_writable: true` writes the
  workspace's `.git` like the rest of the workspace: it commits. A writable mount (`:rw`) of that `.git` or of a
  folder in it is then a mount in the workspace and needs no entry in `writable_mounts`.
- **What it means.** Every package you run with a writable workspace can leave hooks, a config, a `commondir` or
  the state of a rebase in that repository, and the next git command you run there runs them, on the host and as
  you (docs/security.md, "A writable workspace beyond its top-level `.git`"). Set it in a home where you wrote the
  packages that write a workspace. The value is `true` or `false`; anything else refuses the file (exit 6).
- **Without it.** `false`, `null` or missing (the default): the `.git` is read-only. A package that commits in your
  workspace fails at that step with git's `Read-only file system`. A `.git` that is a symbolic link cannot be
  mounted read-only in its place: the step is refused when it runs (exit code 125, nothing started). A writable
  mount that is that `.git`, lies in it or holds it is refused, in a folder you listed too.
- **What needs neither.** The checkout of `run --source` ("Isolated workspace"): its `.git` is writable with the
  checkout, since it is the run's data and Traceworks runs no git in it after a step ran in it. A package that
  commits works there without the setting.
- **What it does not reach.** A host run has no mounts: a command on the host writes all of `.git`, with or without
  the setting. Only the workspace's own top-level `.git` is read-only: a repository deeper in the workspace, and a
  workspace without a `.git`, where a step can make one, are not covered.

`traceworks describe <package>` says which holds in this home for every block with a writable workspace:
`workspace writable, its .git read-only` or `workspace writable, with its .git (workspace_git_writable)`.

## AI images

Every AI call runs its CLI in a built-in image (`claude` or `pi`, built by `traceworks images build`). `ai_images` in
`traceworks.json` lets the operator run a CLI in an image of their own instead: one that is mirrored in a registry
they trust, carries a different version of the CLI, or has a CA bundle their network needs.

```json
{"ai_images": {"claude": "registry.example.org/ops/claude:2.1"}}
```

An AI step of that CLI then runs in the operator's image, and everything else stays as it is for the built-in image:
the same command line (the CLI's program and options, so the image must provide it under the name the built-in
image does, `claude` or `pi`, and an `sh`), stdin, standard mounts, tool policy, credentials, limits and network.
What it changes:

- **The start check** asks for the operator's image in place of the built-in one, and no longer asks for the built-in
  image of that CLI. A missing image is refused (exit 6) with a hint that names the setting: build or pull it yourself.
- **Traceworks never builds or pulls it.** `traceworks images build` still builds the built-in images, and a run
  never builds or pulls.
- **The record:** `run.started` and `run.resumed` carry `ai_images` (docs/events.md), and each AI call's
  `step.command` names the operator's image and the image ID it started from. `run` and `resume` print one line
  for each replacement on stderr: `traceworks: note: ai_images: claude runs in <image> instead of the built-in image`.

What it does not change: only the operator sets it. A package cannot name or change it (it is no workflow or block key),
and no environment variable sets it. It gives a model no more than the built-in image gives it: the image is
as trustworthy as the operator makes it, and it gets the provider's credentials and the workspace as the
built-in image would (docs/security.md, "AI steps"). A `container_image` of a block is not an AI image: it does not
change the image of an AI call.

## The network of an AI step

What a model may do is the block's `tools`. Where its container can connect is a second switch, and it is the
operator's alone: by default the container of an AI call reaches the API of its provider and nothing else, and the
record names every connection it asked for. It needs no configuration.

- **The hosts** are fixed in Traceworks, never taken from a workflow: `api.anthropic.com:443` for Claude; for Pi the
  API of the backend the call's model names (`anthropic/...` or no backend: `api.anthropic.com:443`, `openai/...`:
  `api.openai.com:443`, `google/...`: `generativelanguage.googleapis.com:443`, `openrouter/...`:
  `openrouter.ai:443`). A Pi call whose backend is a model server you declared reaches that server and none of
  these hosts ("Local models"). A Pi call with any other backend has no host and no credential: it fails before
  anything runs, and the message names the backends Traceworks knows ("AI steps", "Credentials").
- **`ai_hosts`** in `traceworks.json` adds hosts for every container of one CLI: a gateway or a mirror your provider
  traffic goes through.

  ```json
  {"ai_hosts": {"claude": ["gateway.example.org:8443"], "pi": ["llm.example.org:443"]}}
  ```

- **How it is enforced.** The container joins one network that has no way out. Next to it Traceworks starts the
  egress proxy (the built-in image `egress`, built by `traceworks images build`), which is on that network and on one
  with a way out, and points the container's clients at it (`HTTPS_PROXY`, `HTTP_PROXY`, `https_proxy`,
  `http_proxy`; a step cannot set these names itself). The proxy tunnels `CONNECT <host>:<port>` to the listed hosts
  and answers everything else with 403: another host, a plain HTTP request, an IP address that is not listed. The
  one plain HTTP request it sends on is one to a model server of yours whose URL starts with `http://` ("Local
  models"). A target is compared as the client writes it. The proxy and both networks carry the run's label and are removed when
  the call has ended, also after a timeout or a cancel. When the proxy does not listen within 15 seconds, or a
  network cannot be created, nothing of the call runs and it fails: its `step.command.finished` has exit code 125.
- **The record.** The call's `step.command` says `"network": "hosts"` and lists them in `network_hosts`; its
  `step.command.finished` lists what the container asked the proxy for in `egress`, by target and verdict: `ALLOW`,
  `DENY`, `FAIL`, or `LIMIT` for the target `*` once the proxy has refused everything after 10,000 requests
  (docs/events.md). Only the target is recorded, never a path, a header or content. `show` and
  `run.html` show both, mark a `DENY`, and count the denied connections of the whole run. A denied connection fails
  no step and changes no gate: it is an observation. A CLI may ask for hosts it does not need, such as a telemetry
  or update host, and work without them; you see them here, and add one to `ai_hosts` only if you want it reached.
- **`ai_network: "open"`** turns all of this off: the container of an AI call gets the runtime's default network, as
  before this existed, no proxy starts, and no connection is recorded. `run` and `resume` then print
  `traceworks: note: ai_network: open: the container of an AI step can connect anywhere, not only to its provider`,
  and for each `ai_hosts` entry of a restricted network `traceworks: note: ai_hosts: the containers of <cli> also
  reach <hosts>`. `run.started` and `run.resumed` record both settings.
- **The runtime.** Docker needs version 25 or newer, the first whose `run` takes a container onto two networks at
  once (as its release notes say; not tried with an older one). From version 28 the host itself has no address on
  the step's network. Verified with Docker 29.1.3 only. Podman gets both networks as one list (`--network a,b`);
  nothing about Podman is verified. A run with AI steps holds two networks, `traceworks-<run id>-net` and
  `-out`, from its first AI call until it ends in its process (also when it parks or pauses), whatever the number
  of its AI calls: so many runs with AI steps at the same time can use up the runtime's address pools, and a call
  is then refused with the runtime's message. Two AI calls of a run never run at the same time, so they never share
  the networks while running. On a desktop machine each network is a device the network services react to; see
  "Many containers on a desktop machine" in docs/security.md.

What it does not close is in docs/security.md, "What they do not protect": the provider's own host stays reachable.

## Local models

A model on a server of your own: with it no prompt, no file and no answer of an AI step goes to a provider. Only a
`PI` step can use one, and only the operator declares the server, in `traceworks.json`. No workflow, workspace, run
input or environment variable names a server or its URL.

```json
{"local_models": {
  "llm": {
    "url": "http://llm.internal:8080/v1",
    "api": "openai-completions",
    "models": ["qwen2.5-coder:7b", "llama3.3"],
    "secret": "LLM_KEY"
  }
}}
```

| Key | Value |
|---|---|
| the key of an entry | the backend id, of your choosing: `[a-z][a-z0-9-]{0,31}`, and none of `anthropic`, `openai`, `google`, `openrouter` |
| `url` | the base URL of the server's API, as the server documents it for OpenAI clients: `http://` or `https://`, a host name in small letters or an IPv4 address, an optional port (80 or 443 without one) and an optional path of plain segments. No user, no query, no fragment, no IPv6 literal, and not this machine's loopback (below) |
| `api` | `openai-completions`: the chat completions API of OpenAI (`POST <url>/chat/completions`), which Ollama, llama.cpp's server, vLLM and LM Studio speak. It is the only kind in this version |
| `models` | the model names a step may ask the server for, a closed list of 1 to 64 names, each as the server knows it: parts of letters, digits and `. _ : + -` with `/` between them, at most 128 characters. A name cannot end in `:off`, `:minimal`, `:low`, `:medium`, `:high`, `:xhigh` or `:max`, which Pi reads as a thinking level |
| `secret` | optional: the name of the secret that holds the server's key, for a server that wants one. The value comes from `credentials.json` (`traceworks credentials set LLM_KEY`), or from the environment when `secret_names` lists the name. It is no name of a provider's credential and none the CLI reads as a setting (`PI_...`, `NODE_...`, `TRACEWORKS_...`) |

**In a workflow** a step asks for the model as `<backend>/<model>`, as a literal or as an option of a model choice
("Model choices"):

```json
{"type": "simple", "name": "review",
 "config": {"command": "Review the diff.", "provider": "PI", "model": "llm/qwen2.5-coder:7b"}}
```

A thinking level goes into `effort`; `llm/qwen2.5-coder:7b:high` is no model of the list.

**`traceworks check`** reads `traceworks.json` of the home it runs with (`--home`, `TRACEWORKS_HOME`, or
`~/.traceworks`), as every command does. A backend that home declares is known; a backend nobody declared stays the
error `unknown-model-backend`, and so is a model its server does not list. So a package that uses a local model
checks clean in the home that declares the server, and has an error in a home that does not: there it could not
run. `traceworks describe` names the server and its URL next to the model.

**What a run does with it.**

- *The start check* refuses the run (exit 6) before its first step when a model names a backend nobody declared,
  when the server does not list the model, and when the server's `secret` has no value. It sees the workflow and
  the children it names by a literal `workflow_id`. A child named through a placeholder is not seen: its step is
  judged when it runs. There a model its server does not list and a model `allowed_models` does not allow are
  still refused, and a server with a `secret` has no value and is refused too; a call to a declared server without
  a `secret` runs, since it needs nothing the start check would have read. It asks for no credential of
  a provider: a run whose `PI` steps all name local models needs none and reads none. A step that goes to a provider
  next to them still needs that provider's credential. `allowed_models` applies as to every model:
  `[{"provider": "PI", "model": "llm/*"}]` allows this server's models and no provider's.
- *Pi's configuration* is written by Traceworks, from your setting and from nothing else. Pi knows such a server
  as a provider of its own configuration file, `models.json` in its agent directory; no extension is loaded, and
  every option of a Pi call stays (`--offline`, `--no-extensions`, `--no-mcp`, `--no-approve`, `--no-skills` and
  the rest). The file names one provider, always `traceworks-local`, with your URL, the API and the one model of
  the call:

  ```json
  {"providers":{"traceworks-local":{"baseUrl":"http://llm.internal:8080/v1","api":"openai-completions","apiKey":"$LLM_KEY","authHeader":true,"models":[{"id":"qwen2.5-coder:7b"}]}}}
  ```

  It reaches the container as the system prompt does: base64-encoded in the environment variable
  `TRACEWORKS_PI_MODELS`, which a fixed script decodes into `/tmp/traceworks-pi-agent/models.json` in the
  container's own `/tmp` and drops, before it starts Pi with `PI_CODING_AGENT_DIR=/tmp/traceworks-pi-agent`. The
  key is never in the file: `"$LLM_KEY"` is Pi's own way to read it from its environment, where the value travels
  as every credential does. For a server with a `secret` the file also says `"authHeader": true`, Pi's setting for
  sending the key as `Authorization: Bearer`, which is off by default. A server without a `secret` gets the word `none` as its key, since Pi offers a model
  only when its provider has one. Pi is called with `--provider traceworks-local --model <model>`.
- *The network.* The container reaches that one server, through the egress proxy, and nothing else: not the hosts of
  any provider, not your `ai_hosts`, which are for calls to a provider. This holds with `ai_network: "open"` too: a
  local step always runs behind the proxy. Every connection is in the record as for any AI call.
- *The record.* `run.started` and `run.resumed` name your servers (`local_models`: URL, API, models and the name of
  the secret, never its value). The `ai.request` of each call to one says `"local": {"backend": "llm", "url":
  "http://llm.internal:8080/v1", "api": "openai-completions"}`; a call to a provider has no such field. `show` and
  `run.html` print `local backend llm at http://llm.internal:8080/v1` with the call, and `diff` counts a call that
  moved between a provider and a server, or to another URL, as another setup. `run` and `resume` print one note per
  declared server. The secret is redacted like every credential.

**Where the server may be.**

- *Elsewhere in your network, or anywhere a name or an address leads*: supported, by name or by IPv4 address. A
  name you list is connected to as it is, a private address included: you named it. The proxy resolves the name,
  from its own container, with the name servers the container runtime gives it: a name that only this machine's
  `/etc/hosts` knows does not resolve there, so write the address instead. A server that cannot be reached is a
  `FAIL` in the call's `egress`, and the call fails with Pi's error.
- *On the machine that runs Traceworks*: supported through an address of the machine that containers reach, not
  through its loopback. `localhost`, `127.0.0.1` and `0.0.0.0` are refused when the file is read, since the loopback
  of a container is its own, and the message says what to write instead. The refusal is a hint against a common
  mistake, not a barrier: any other name or address of the machine is taken as written. Bind the server to one
  address of the machine that the proxy's container reaches, such as the machine's address in your network (Ollama:
  `OLLAMA_HOST=192.168.1.20`; llama.cpp's server: `--host 192.168.1.20`), and write that address in `url`; a
  firewall rule that lets only the container runtime's networks in narrows it further. Listening on every address
  (`0.0.0.0`) works too, and opens more: the server is then reachable from your whole network, and, with a Docker
  older than 28 or with Podman, directly from the container of every AI step, a provider's steps included, past the
  proxy and without a line in `egress`. With Docker 28 or newer the machine has no address on the step's network, so
  a step's container reaches the server only through the proxy, and only the one `host:port` of the URL. With an
  older Docker or with Podman that is not so (docs/security.md, "The host's own services, on an older runtime"):
  what the machine serves on an address of the step's network is reachable from the step, whatever the proxy
  allows. Traceworks prints no warning for it at a run: `local_models` is checked before a container runtime is
  looked for, and which address of the machine a network gets is the runtime's choice.
- *Plain `http://`*: supported, since a local server usually speaks it. The proxy never forwarded plain HTTP
  before; it now does so to exactly the servers whose `url` starts with `http://`, and to nothing else. A tunnel to
  that `host:port` already carries any bytes, so forwarding reaches nothing a tunnel does not; the proxy sets the
  `Host` header of a forwarded request to that `host:port`, whatever the client wrote. What it means for
  you: the prompt and the key travel unencrypted between this machine and the server. Use it on a network you
  trust, or `https://`.
- *`https://`*: supported for a server whose certificate a public authority signed, since the `pi` image holds
  the certificate authorities of Node.js only. A certificate of a private authority is refused by Pi, and
  Traceworks has no setting to add one.

**Not verified.** This was built from the documentation of Pi 1.5.2, and no real model was called. A container
test with the real Pi CLI against a stand-in server exists (`AiLocalModelIT` in `integration-tests`); read
docs/security.md, "A local model", for what is still open: how Pi sends the key, whether it asks the server for
anything besides the chat completion, and how it sends a plain HTTP request through a proxy. Two more things are
open that are no matter of security. The file names a model by its id alone, so Pi takes its own defaults for the
rest: a context window of 128,000 tokens, at most 16,384 output tokens, no thinking; a model with a smaller window
may refuse a long prompt. And Traceworks writes none of Pi's `compat` settings, so which request fields Pi sends
to a server it does not know (the role of the system prompt, the field of the token limit) is Pi's choice; a
server that rejects one of them fails the call, and the error is in the record. Only Docker is meant; nothing about
Podman is verified.

## Web tools of an AI step

Whether a model may use the web is a third switch, next to what it may do in the workspace (`tools`) and where its
container can connect (the operator's `ai_network`). It is the block key `web`, on every block that owns `tools`:
`simple`, `agentic`, and `foreach`, `retry`, `while` and `conditional` for their AI step.

| `web` | The model gets | Its container reaches |
|---|---|---|
| missing or `none` | nothing more | its provider only |
| `search` | the provider's search tool (Claude: `WebSearch`). The search runs at the provider, not in the container | its provider only |
| `fetch` | the search tool and the fetch of a page (Claude: `WebSearch` and `WebFetch`). The page is fetched from the container | its provider, and the hosts in `web_hosts` |

```json
{"type": "simple", "name": "read_the_docs",
 "config": {"command": "What changed in the last release?", "web": "fetch", "web_hosts": ["docs.example.org"]}}
```

- **`web_hosts`** is required with `fetch` and refused without it. An entry is a host (`docs.example.org`, port 443)
  or `host:port`, with the host grammar of `ai_hosts` and in lower case in the record; or the list is exactly
  `["*"]`, any host. A star is not mixed with names, and neither key takes a placeholder: a run's data must not widen
  what its model may reach.
- **`["*"]` is any public host, not any host.** The container still has no way out but the egress proxy, so every
  connection is in the record. For a target that only the star allows, the proxy refuses (`DENY`) every port but
  443, every IP address, and every name that resolves to an address that is not public: the loopback, private and
  link-local ranges (10/8, 172.16/12, 192.168/16, 169.254/16), 100.64/10, multicast, and the IPv6 forms of these. It
  resolves the name once and connects to the address it checked. A host you list by name is reached as you wrote
  it, whatever it resolves to and on the port you gave.
- **Independent of `tools`:** `tools: none` with `web: search` gives the model the search tool and nothing else. The
  web tools are passed the way the other tools are: named in the CLI's tool list (`--tools WebSearch`).
- **Always untrusted input.** What a model reads from the web is foreign content. A step with web behaves in every
  respect as one with `untrusted_input: true`: its call gets the foreign-content notice, its input and output are
  searched for instructions aimed at an AI, and a hit turns the run's gate yellow. `untrusted_input: false` next to
  web is refused, not overridden.
- **The requirement:** a value other than `none` needs `ai.web` in `requires`. Without it the workflow is refused
  (`check`: `missing-requirement`); declared and unused it is a warning (`unused-requirement`).
- **Pi has no web tool.** `web` other than `none` on a step that runs on Pi (by its own `provider` or the
  workflow's) is refused, and `check` reports `unsupported-web`.
- **Refused the same way** (`check`: `invalid-config-value`; the run admission refuses the workflow): a value that is
  no mode, `fetch` without `web_hosts`, `web_hosts` without `fetch`, an entry that is no host.
- **Never:** an AI condition, `compress-ai` and an AI actor have no web, whatever their block says.
- **The record:** `ai.request` names `web` next to `tools`, and for `fetch` the `web_hosts`; the call's
  `step.command` lists them among its `network_hosts`, and its `egress` names every connection. Each use of a web
  tool is an `ai.tool_call` with the query or the URL. `show` and `run.html` name the mode with each AI call and in
  the list of the workflow's blocks. The step's metadata has `webSearchRequests` and `webFetchRequests` when the CLI
  reports them.
- **With `ai_network: "open"`** web works as well: the tools are a switch of their own. `web_hosts` is then recorded
  but nothing enforces it, since the container can connect anywhere; `show` and `run.html` say so where they name the
  open network.

What web opens is in docs/security.md, "Web tools".

## Tool mode of the shell block

A `shell` block with `container_args` instead of `command` is a **tool step**: the image's own entrypoint runs with
those arguments, with no shell in between. It is for a ready-made tool (a scanner, a linter) that has its own image.

```json
{"type": "shell", "name": "scan", "config": {
  "container_image": "ghcr.io/example/scanner:1.4",
  "container_args": ["fs", "--format", "sarif", "--output", "/out/scan.sarif", "/workspace"],
  "output_format": "sarif"}}
```

A block takes `command` or `container_args`, not both. A block with `container_args` and no `container_image`, or with
`container_args` that is no list, fails when it is reached, and the run ends `FAILED` (exit 3) whatever the block's
`on_failure` says. `traceworks check` reports both as errors: `missing-required-config-key` for the missing
`container_image` and `invalid-config-value` for `container_args` that is no list (exit 6).

| Key | Meaning |
|---|---|
| `container_image` | the image to run; required. A registry image, or a package image as `./images/<name>` (see "images") |
| `container_args` | the arguments, a list of strings; required, and it may be empty. They are passed as they are, one argument each: a space, `;`, `$(...)` or `*` in one is not interpreted |
| `output_format` | `sarif` makes the step output a fact sheet (below); any other value, or no key, keeps the tool's output as it is |
| `output_dir` | where the tool writes its SARIF files in its container (default `/out`); read only with `output_format: sarif` |
| `fact_prefix` | the prefix of the fact sheet; default the block's name. It is lower-cased, every run of characters other than `a-z`, `0-9` and `_` becomes one `_`, and `_` at its ends is dropped (`Scan-Tree` gives `scan_tree`); a prefix with nothing left is `tool` |
| `cache_dir` | where the tool's persistent cache folder appears in its container (see "The cache folder") |
| `mounts` | more mounts, as for a command, each with `:ro` or `:rw`; a writable one must lie in a folder of the operator's `writable_mounts` ("Writable mounts") |
| `network` | `true` gives the tool network access; by default it has none |
| `workspace_writable` | `true` mounts the workspace writable, with its top-level `.git` read-only unless the operator set `workspace_git_writable` ("The `.git` of a writable workspace"); by default the tool sees the workspace read-only |
| `timeout_seconds` | how long the tool may run (default 300); a tool that is stopped for it has exit code 124 |
| `secrets` | the names of secrets that reach the tool as environment variables (see "Secrets") |
| `mount_docker_socket` | refused: with `true` the step fails, and `traceworks check` reports `docker-socket-refused` |

The tool starts in `/workspace`, with the package read-only at `/package` and the run's scratch directory writable at
`/scratch`, as every container does (see "Host execution"). It is limited as every container is, and what it prints
counts against the output limit (see "Output limit"). Like every step with a `container_image`, it needs a container
runtime; there is no tool step on the host.

**Placeholders.** `container_args` can name run variables and earlier outputs (`{{ target }}`,
`{{ block.build.output }}`), which are resolved before the tool starts. A protected value cannot reach an
argument as a variable, as it can reach a shell line, and a value such as `--config=/etc/x` would change what the tool
does, so such a value is **refused**: an untrusted input, platform data such as `previous_chain_output`, the output
of a block that ran after untrusted data reached a shell line, and, after the run's first call of a model, every
step output and every variable made from one. The step fails before the tool starts, with `container_args must
not contain untrusted values (untrusted input, tainted block output, platform data, text a model wrote): <the
argument>`. A `mounts` entry with such a value is refused too. A `shell` step with `command` takes such a value
as a variable instead (`values`; "check", "Values: how a protected value reaches a command").

**Output.** Without `output_format`, the step output is what the tool printed, standard output and standard error
together. The step is `COMPLETED` when the tool exits with 0 and `FAILED` otherwise, which ends the run `FAILED`
(exit 3) unless the block says `on_failure: continue`. The step's metadata holds `exitCode`, `executionMode`
(`container-tool:<image>`) and `outputFormat` (`raw` or `sarif`). `step.command` names the image as the block wrote it
and the ID it resolved to, and lists every mount with its host path (docs/events.md).

**SARIF.** With `output_format: sarif`, the tool writes SARIF files (`*.sarif` or `*.sarif.json`, directly in the
folder) to `output_dir`. Traceworks mounts a new, empty folder of the host's temporary directory there, reads the
files after the tool has ended, and removes the folder. The step output is a fact sheet and nothing else:

```
## FACT SHEET scan (ittool 1.2.3, 1 SARIF file)
FACT scan_findings: 4
FACT scan_critical: 1
FACT scan_high: 1
FACT scan_medium: 1
FACT scan_low: 1
FACT scan_suppressed: 1
FACT scan_exit_code: 0
HIT scan: src/app.txt:3 CRITICAL IT001 – critical thing
HIT scan: lib/util.txt:7 HIGH IT002 – high thing
```

- The heading names the prefix, the tools that wrote the files (name and version) and the number of files.
- A result's severity is the CVSS score `security-severity` of the result or its rule (9.0 and over `CRITICAL`, 7.0
  and over `HIGH`, 4.0 and over `MEDIUM`, else `LOW`), else its SARIF level (`error` is `HIGH`, `note` and `none` are
  `LOW`, anything else `MEDIUM`). A suppressed result is counted in `<prefix>_suppressed` and listed nowhere else.
- A `HIT` line is the prefix, the location (the path relative to the workspace, with `:<line>` when the result has a
  start line), the severity, the rule ID (`-` without one) and the message. The lines are sorted by severity, then by
  location; after 200, a last line says how many more there are.
- **Code snippets are never printed.** The `snippet` of a region and of a context region are dropped, so a secret
  that a scanner found does not reach the step output, the record or `run.html`. The other texts of the tool are
  shortened and defused.
- **No silent zero.** When Traceworks cannot read a measurement, the sheet says so and the step is `FAILED`, even when
  the tool exited with 0: `NOT MEASURED: <prefix>_findings (<reason>)`, then `FACT <prefix>_exit_code: <code>`, then
  the last 40 lines of the tool's output, each after `| ` (or a note that the output holds SARIF and is not shown).
  The reasons: no SARIF file in `output_dir`; a file that is not SARIF or not readable; a run without `results`;
  `executionSuccessful: false`; files of more than 50 MB together; and a tool that did not run to its end, whatever
  it wrote before: a timeout (exit 124), a container that did not start (125), an entrypoint that is not found or not
  executable (126, 127), a cancel (130), a kill such as the memory limit (137), an output beyond the limit (153), any
  other end by a signal (129 to 159, 128 plus the signal's number, such as a segfault, 139) and 255.
  A report whose `results` is `[]` is measured: the counts are 0.

**The cache folder.** `cache_dir` mounts a writable folder of the Traceworks home at that place in the tool's container:
`<home>/cache/tools/<slug>`. The slug comes from the reference the container runner runs, without tag or digest,
lower-cased, with every run of characters other than `a-z`, `0-9`, `.`, `_` and `-` turned into `_`, and a `_` put in
front of a name that starts with `.`. A registry image is its own reference (`aquasec/trivy:1` gives
`aquasec_trivy`). A package image `./images/<name>` is the tag that `images build` gave it,
`traceworks-pkg/<package id>/<name>:<h>`, so its slug is `traceworks-pkg_<package id>_<name>`: it stays the same when
the image is rebuilt, and two packages with different ids never share it.

A folder belongs to one image name. A name that holds a `_` itself, or the `:` of a registry's port, would join to
the same text as another (`a_b/c` and `a/b_c`, `host:5000/x` and `host_5000/x`), so every name that holds anything
but letters, digits, `.` and `-` between its slashes gets `__` and 12 hex digits at the end of its slug, the start
of the SHA-256 of the name: `registry:5000/a/b:1` gives `registry_5000_a_b__<12 hex>`. That holds for registry
images and for package images alike. Names without such a character keep the folder they had. What moves once,
compared with earlier versions: the cache of an image from a registry named with a port, and of an image whose
name, or whose package id, holds a `_`. The old folder stays where it is and is not used again; move its content
or remove it.

Every block, of any run, that names the same image shares the folder, so
a tool keeps its downloaded databases between runs. A package image that is not built has no reference: the run is
refused as for any missing image, and no folder is created. Traceworks creates the folder when the step starts and never
cleans it. It is not part of the run record.

**What differs from a `shell` step with `command`:**

- no shell: no quoting, no `$VAR`, no pipes. The arguments are the arguments, and an untrusted value is refused
  instead of quoted;
- the image's entrypoint runs, not `bash -c`, so the image need not have a shell;
- the workspace is read-only unless `workspace_writable: true`, as for a command; a tool that writes reports puts them
  in `output_dir` or `/scratch`;
- the output can be a fact sheet instead of text;
- `container_image` is required.

## Secrets

A `shell` step names the secrets it needs in `secrets`. Each one reaches its command as an environment variable of
that name, and the record holds the name, never the value:

```json
{"type": "shell", "name": "deploy", "config": {"command": "./deploy.sh", "secrets": ["DEPLOY_TOKEN"]}}
```

- **Where `secrets` works:** on a `shell` block, its tool mode (`container_args`) included, and on `conditional`,
  `retry`, `while` and `foreach` in shell mode and the shell blocks of a `switch` case, for their inner shell steps.
  Only the step that declares a secret gets it.
- **Names:** a name is literal (no placeholder) and matches `[A-Z_][A-Z0-9_]*`. Names the step's environment or the
  container client sets are refused: `PATH`, `HOME`, `TMPDIR`, `LANG`, `USER`, every name that starts with
  `TRACEWORKS_`, `LC_` or `TINI_` (the container's init reads those), the names the container client reads
  itself (`DOCKER_*`, `CONTAINERS_*`, `LD_*`, the proxy variables and others), and the credential names of the AI
  providers (`ANTHROPIC_API_KEY`, ..., see "AI steps"), which only a provider's containers get; store such a value
  under another name for a step. Such a workflow is refused at start (exit 6) and by the run admission, and
  `traceworks check` reports `invalid-secret-name`.
- **Values:** from `<home>/credentials.json`, else from the environment variable of the same name in the Traceworks
  process, when `secret_names` in `traceworks.json` names it. Without the key no name is taken from the
  environment: a package you did not write can declare any name, such as `AWS_SECRET_ACCESS_KEY`, and would
  otherwise get the value your shell holds. With `"secret_names": ["DEPLOY_TOKEN"]` only that name reaches a step
  from the environment, and the start refusal says when a value is set but not allowed. `traceworks credentials set DEPLOY_TOKEN` stores a value in the file without the value passing
  through the shell's history, and creates the home and the file where they are missing (see "credentials"). The file
  is a JSON object of names and string values, and may be written by hand as well:

  ```json
  {"DEPLOY_TOKEN": "..."}
  ```

  Only its owner may read or change it (`chmod 600 <home>/credentials.json`); a file its group or others can read or
  change is refused, and so is one on a file system without POSIX permissions, where Traceworks cannot tell who may
  read it (set the secrets as environment variables there). The file is read when a run that declares a secret starts
  or resumes; no message shows what it holds.
- **Start check:** before a run is created, and before a resume records anything, every name the workflow declares
  (in nested blocks, and in the workflows its `sub-workflow` and `foreach` blocks name literally in `workflow_id`)
  needs a value. The value has at least 8 characters, holds no `"`, `\`, `'`, control character (below U+0020, or
  U+007F) or lone surrogate, has no whitespace at its start or end (shell outputs are trimmed), is not only digits
  nor reads as a JSON number (such as `-1234.5678`), holds nothing that the cleaning of untrusted text changes (two
  whitespace characters in a row, U+0085, U+2028 or U+2029, a marker such as `gate_status:` or `todo_item:`, a
  block boundary such as `<open_todos>`), and does not contain `[redacted:`: the record could not redact
  such a value reliably (docs/events.md, "Redaction"). Otherwise the run is refused (exit 6), naming every secret
  and the rule it breaks, never the value. A child workflow named by a placeholder cannot declare secrets of its own: its step
  fails, naming the secret.
- **Command lines:** a step's command reaches bash on stdin, never as an argument, on the host and in a container,
  so no other process on the machine reads it from the process list. That matters because a later step's command
  can hold a secret value that an earlier step printed. A tool's `container_args` stay arguments. A command that
  does not arrive whole runs nothing and ends with exit code 74. On a timeout or cancel the running command gets
  SIGTERM, and its own TERM trap runs to its end; nothing after it runs, unless the command sets its own TERM
  trap. A command that SIGTERM ended, also one that sends the signal to itself, fails with exit code 143.
- **Redaction:** every value the run passes on is replaced with `[redacted:<NAME>]` in everything Traceworks writes
  and prints, `run.html` and the checkpoints included (docs/events.md, "Redaction"). Redaction finds a value only as
  it is: a step that prints its secret encoded or in pieces is not stopped by it.

## AI steps

An AI step's model only answers unless its block says what else it may do. `simple` and `agentic` take the key
`tools`, and so do `foreach`, `retry`, `while` and `conditional` for their AI step:

| `tools` | The model may |
|---|---|
| `none` (the default) | only answer |
| `read` | read and search files |
| `write` | read, search and change files |
| `shell` | read, search and change files, and run shell commands |

```json
{"type": "agentic", "name": "fix", "config": {"goal": "Make the tests pass", "tools": "shell",
  "workspace_writable": true}}
```

- **Every call of the block** carries its `tools`: an `agentic` block's reasoning calls as well as its actions. An
  `agentic` block must state `tools`; without it the workflow is refused, and `traceworks check` reports
  `tools-required`. A value outside these four is refused too, and the error lists the valid ones (`unrestricted`,
  which only Codex took, is such a value); `tools` is never a placeholder.
- **A wrapper's AI step** (`foreach`, `retry`, `while`, `conditional` with `execution_mode: ai`) carries the
  wrapper's `tools`, `none` without the key: a `foreach` that judges one file per item can give its model `read`, so
  it can follow a call into the rest of the workspace. A `simple` block in a `switch` case is a simple block and
  carries its own `tools`.
- **Without tools:** an AI condition (`conditional`, `while`), `compress-ai` and an AI actor of an `approval` or
  `human-input` always run with `none`: they answer a question and do no work.
- **The workspace:** an AI step sees the workspace read-only, as a `shell` step does, unless its block sets
  `workspace_writable: true`. With `write` or `shell` and a read-only workspace, the model can change only `/tmp`
  and the run's scratch directory.
- **MCP servers:** an AI step gets none. `mcp_servers` takes only `[]`, and `user_mcp_servers: true` (the servers of
  the CLI's own configuration) is refused (`unsupported-mcp-servers`).
- **The requirement:** a `tools` value other than `none` needs `ai.tools` in `requires` (see "Requirements").
- **Web** is a switch of its own, next to `tools` ("Web tools of an AI step").

**Where an AI call runs.** Every AI call runs its provider's CLI in a container, in a host run too: the built-in
image `claude` or `pi` (`traceworks images build` builds them), with the CLI as the image's entrypoint, the
prompt on stdin, a network that reaches only its provider ("The network of an AI step"), the run's standard mounts (the workspace read-only unless `workspace_writable`, the
package read-only, the scratch directory writable) and the call's `timeout_seconds` (300 seconds by default). It goes
through the same command path as a `shell` step, so the record has its container command too.

**The provider** of a step is the block's `provider`, else its workflow's, else `CLAUDE`; **the model** is the
block's `model`, else its workflow's when the block does not switch to another provider, else the provider's
default. Both are written as they are: a placeholder in `provider` is an error (`unknown-provider`), and so is one in
`model` (`placeholder-in-model`), since no run resolves either. A step whose model a run may pick refers to a
declared choice instead ("Model choices"); it then uses the provider and the model of the picked option. A provider the run uses is
one of its workflow's steps, or of a workflow its `sub-workflow` and `foreach` blocks name literally in `workflow_id`,
at any depth; the start check needs the image and a credential of each (see "Host execution"). Of a step that refers
to a model choice, it needs those of the option the run picked, and nothing of the options it did not pick. The start check does
not follow a reference with a placeholder, nor one that names no workflow:

- a reference that names no workflow fails its step when it runs;
- a workflow named by a placeholder runs, but the start check has not seen it. An AI step of it whose provider the
  start check read no credentials for fails before its CLI starts, with `AI provider <ID> has no credential in this
  run: ...`, which names the credentials; name the workflow by a literal `workflow_id`, and the start check reads
  them. A provider the start check read credentials for, for another workflow of the run, gets them. When the
  image of its provider is missing, nothing starts and the call fails: its `step.command.finished` has exit code
  125, and the refusal names the image and `traceworks images build`.

**Credentials.** A provider's CLI gets its credentials as environment variables of its container, from
`<home>/credentials.json` or the environment of Traceworks, like secrets ("Secrets"):

| Provider | Credential names |
|---|---|
| `CLAUDE` | `ANTHROPIC_API_KEY`, `CLAUDE_CODE_OAUTH_TOKEN` |
| `PI` | `ANTHROPIC_API_KEY`, `ANTHROPIC_OAUTH_TOKEN`, `OPENAI_API_KEY`, `GEMINI_API_KEY`, `OPENROUTER_API_KEY` |

- Every name of `CLAUDE` that has a value goes to that provider's containers, and to no other container or step: no
  step can name one in `secrets`.
- A Pi call gets only the credential of the backend its model names, whatever other names have a value:
  `ANTHROPIC_API_KEY` and `ANTHROPIC_OAUTH_TOKEN` for `anthropic/...`, for a model without a backend and for a step
  without a model; `OPENAI_API_KEY` for `openai/...`; `GEMINI_API_KEY` for `google/...`; `OPENROUTER_API_KEY` for
  `openrouter/...`. A model written as a literal is judged before the run starts: the start check refuses the run
  (exit 6) when the backend of such a model has no credential with a value, naming the model and the names, and a
  step without a model counts as `anthropic`. A model whose backend is a model server you declared needs none of
  these: it gets the server's secret, when it has one, and nothing else ("Local models"). A model with any other
  backend is an error of `traceworks check` (`unknown-model-backend`) and refuses the run at its start, since
  Traceworks cannot tell which credential it takes: the message names these four backends and the servers
  `local_models` declares. The model of a
  picked option of a model choice is a literal like any other, so its backend needs its credential at the start. A
  placeholder in `model` refuses the workflow (`placeholder-in-model`).
- A run that uses a provider with none of them is refused at start (exit 6), naming the names, the command that
  stores one with the path of the file it writes, and the name a Claude subscription's token goes under (below).
  `traceworks run --help` lists the names too. A value follows the rules of a secret's value, so a value the record
  could not redact refuses the run too.
- Only the credentials of the providers a run uses are read, those of its literal child workflows included.
- Every value is redacted like a secret, also where the model quotes it.

**A Claude subscription instead of an API key.** A login of a CLI on the host (`~/.claude`, `~/.pi/agent`) never
reaches a container: nothing of it is mounted or copied. A subscription is used through a token:

1. On the host, run `claude setup-token` (Claude Code's own command; it needs a Claude subscription) and log in in
   the browser it opens. It prints a long-lived token.
2. Store the token under `CLAUDE_CODE_OAUTH_TOKEN` for the provider `CLAUDE`, under `ANTHROPIC_OAUTH_TOKEN` for `PI`,
   or under both:

   ```bash
   traceworks credentials set CLAUDE_CODE_OAUTH_TOKEN    # paste the token; the terminal does not show it
   ```

   The command writes `<home>/credentials.json` for its owner alone and creates the home when this is the first
   thing you do with Traceworks. Without the file, export a variable of that name in the shell that starts
   Traceworks.
3. Start the run. The value goes to that provider's containers as an environment variable of that name, to nothing
   else. It is in no command line, and the record shows `[redacted:<NAME>]` in its place.

Give a provider only the credential it shall use: when an API key has a value too, both reach the container and the
CLI chooses. For Pi with such a token, see "The credential" under "**Pi.**" below and docs/security.md.

**The record.** Each call writes `ai.request`, the CLI's `step.command` and `step.command.finished`, one
`ai.tool_call` per tool call and `ai.response` (docs/events.md); `show` lists the calls of each step and their tool
calls, and `run.html` shows the prompt, every tool call with its arguments and result, and the answer. A call that
failed says why in both, with the first line of the CLI's error (at most 200 characters; the whole error is the
`error` blob of `ai.response`):

```
      ai CLAUDE  model opus  tools read  exit 1  0 in / 0 out tokens  0 tool calls
        error Credit balance is too low
```

**The AI CLIs.** This version configures two: Claude Code (`claude`), provider `CLAUDE`, and Pi (`pi`), provider
`PI`. Both read the prompt on stdin, and neither gets the system prompt on a
command line, since it can hold a value from an earlier step's output. They get it base64-encoded in the
environment variable `TRACEWORKS_SYSTEM_PROMPT` (no step can name a secret so), and a fixed script in the container
decodes it into a file in the container's own `/tmp` (mode 600) before it starts the CLI, which reads the file. A
call without a system prompt gets neither the variable nor the file. A system prompt over 95 KiB in UTF-8 fails such a
call before anything runs: Linux takes at most 128 KiB in one environment variable.

Whether a call has a system prompt, and what is in it, depends on the call. The run's *instructions* are the
workflow's `instructions` under the heading `## Workflow-Specific Instructions`, cut after 10,000 characters; the
*foreign-content notice* is the text that tells the model to treat the content as data, added where the block sets
`untrusted_input: true`, or has web tools (`web` other than `none`), which counts as the same.

| Call | System prompt | Effect on the CLI's own |
|---|---|---|
| `simple` step, also in a `switch` case | the instructions, plus the notice if the block sets `untrusted_input`; none if neither applies | adds |
| `agentic`, reasoning | always one: the reasoning engine's text (goal, success criteria, answer format), with the instructions and, if set, the notice in it | replaces |
| `agentic`, acting | the instructions, plus the notice if the block sets `untrusted_input`; none if neither applies | adds |
| `agentic`, shortening an oversized action | none | none |
| `compress-ai` | its own fixed condensing text; never the instructions or the notice | adds |
| AI condition of `conditional` or `while` | none, the instructions are not passed either | none |
| AI step of `retry`, `foreach`, `while`, `conditional` | like a `simple` step, with the wrapper block's `untrusted_input` | adds |
| AI actor of `approval` or `human-input` | the instructions; never the notice | adds |

So an acting call of an `agentic` block, like a `simple` step, runs without a system prompt when its workflow has no
instructions and the block sets no `untrusted_input`.

**Codex is not supported.** Traceworks refuses a workflow whose `provider` (the
workflow's, or a block's own on an AI call) is `CODEX`, in any case, with "Codex is not supported; Traceworks runs
Claude and Pi" (exit 6). A run of such a workflow is refused before its first block. A run that reaches one as a
child, by a literal `workflow_id` of a `sub-workflow` or `foreach` block at any depth, is refused by its start check,
before the run exists, naming the child; a child named by a placeholder is refused when its step runs, and that step
fails. `traceworks check` reports the error `unsupported-provider` for the workflow it checks and, at the block that
leads to it, for each child it reaches by a literal `workflow_id`. Any other unknown provider is `unknown-provider`.

**Claude.** Each call is `claude -p` with the CLI's stream on stdout:

- **The system prompt** file is `--system-prompt-file` for a reasoning call and `--append-system-prompt-file` for
  every other call.
- **Tools:** `none` is `--tools ""`; `read` is `Read`, `Grep` and `Glob`, `write` adds `Edit` and `Write`, and
  `shell` adds `Bash`, each with `--dangerously-skip-permissions`, since no one can answer a permission prompt in a
  container. A reasoning call of an `agentic` block takes at most 3 turns.
- **Nothing from the workspace:** the CLI starts in the workspace, which configures nothing. It reads no settings
  file (`--setting-sources ""`: none of the user, the project or the checkout, so none of their hooks, permissions,
  environment, MCP servers or plugins, and none of the project's commands, skills and agents), runs no hook
  (`--settings '{"disableAllHooks":true}'`), reaches no MCP server (`--strict-mcp-config`) and reads no `CLAUDE.md`
  or rules file (`CLAUDE_CODE_DISABLE_CLAUDE_MDS=1`). This holds for both credentials, so a workflow behaves the
  same with either. What the model is told is in its prompt and system prompt.
- **A prompt is text, never a command:** the CLI runs no slash command and no skill (`--disable-slash-commands`).
  A prompt that starts with the name of one of the CLI's own commands, such as `/compact`, does not run it; the CLI
  answers that the command "isn't available in this environment" instead of asking the model, and that reply is the
  step's answer, in the record too. Any other prompt reaches the model. Do not start a prompt with `/`.
- **The outcome:** the answer is the text of the CLI's result line. A stream without a result line, a result line
  that reports an error (such as `Not logged in` or the turn limit) and a non-zero exit code fail the call; its
  error is the CLI's message, or the end of the stream, and then the end of the CLI's stderr.

**Pi.** Pi calls the model of any backend it knows (Anthropic, OpenAI, Google, OpenRouter and more) with that
backend's credential. The `pi` image has Pi 1.5.2, and everything below was checked with it. Each call is
`pi -p --mode json` with the CLI's event stream on stdout:

- **The model** names its backend: `google/gemini-2.0-flash` goes to `google`, and
  `openrouter/meta-llama/llama-3.3-70b-instruct` to `openrouter` with the model `meta-llama/llama-3.3-70b-instruct`.
  Without a slash the backend is `anthropic`. A step without a model leaves the choice to Pi,
  which takes the first backend it has a credential for; its container holds the credential of `anthropic` only
  ("Credentials"), so that is the backend. A backend other than `anthropic`, `openai`, `google` and `openrouter` is
  refused. `ai.response` names the backend with the model, such as
  `anthropic/claude-sonnet-4-5`.
- **Thinking:** the step's `effort` is Pi's `--thinking` level. Without one, a level after the model's last `:` is
  used (`sonnet:high`; Pi's levels are `off`, `minimal`, `low`, `medium`, `high`, `xhigh` and `max`), and the model
  keeps it.
  Without either a call thinks at `medium`, Pi's own default (a reasoning call with a turn limit maps it:
  1 is `low`, up to 3 `medium`, more `high`; of the calls Traceworks makes, only the shortening of an
  oversized `agentic` action has one, 1). The level is always given on the command line.
- **The system prompt** file is `--system-prompt` for a reasoning call and `--append-system-prompt` for every other
  call; Pi reads a path it is given that exists. The other option is given too, empty.
- **Tools:** `none` is `--no-tools`; `read` is `read`, `grep`, `find` and `ls`, `write` adds `edit` and `write`, and
  `shell` adds `bash`. Pi asks no permission for them, and its other built-in tool, `powershell`, is in no policy.
  No MCP tool is in any policy either (`--no-mcp`, below). `grep` runs `rg` and `find` runs `fd`, which the `pi` image
  has, since Pi downloads nothing (`--offline`, below).
- **Nothing from the workspace:** the CLI starts in the workspace, which configures nothing, read-only or writable
  (`workspace_writable: true`). It trusts no project (`--no-approve`), so none of the workspace's `.pi/` applies:
  no `.pi/settings.json` (which could pick the model of a step that names none, or put a command before each command
  of the `bash` tool), no `.pi/mcp.json`, and none of its extensions, skills, prompt templates, themes or system
  prompt files. It reads no `AGENTS.md` or `CLAUDE.md` (`--no-context-files`), loads no extension,
  which is code, and none of Pi's built-in ones (`--no-extensions`), connects to no MCP server (`--no-mcp`; Pi's
  `--tools` would keep MCP tools), and loads none of the workspace's skills, prompt templates or themes
  (`--no-skills`, `--no-prompt-templates`, `--no-themes`). It installs no package a settings file names, which would
  run the command the settings name for it even without tools, and downloads nothing when it starts (`--offline`).
  No `.pi/SYSTEM.md` replaces its system prompt and no `.pi/APPEND_SYSTEM.md` is added to it, since both system
  prompt options are always given, and no settings file chooses the thinking level, since `--thinking` is always
  given. This holds for every credential. Two things Pi still does with a workspace's `.pi/`: it parses
  `.pi/settings.json` for a session directory, which a call without a session (`--no-session`) does not use (of a
  read-only workspace it cannot lock the file, says so on stderr and goes on), and it renames a writable workspace's
  `.pi/commands/` folder to `.pi/prompts/`, an old layout it moves on.
- **A prompt is text, never a command:** Pi reads no file a prompt names with `@`, and with nothing loaded no
  `/template`, `/skill:name` or extension command expands or runs. Pi's own commands, such as `/compact`, exist only
  in its interactive mode, so the model gets them as text. Pi strips the prompt's leading and trailing whitespace.
- **The credential:** with `ANTHROPIC_OAUTH_TOKEN`, or an OAuth token in `ANTHROPIC_API_KEY`, Pi itself talks to
  Anthropic as Claude Code does: it puts "You are Claude Code, Anthropic's official CLI for Claude." before the
  system prompt and names its tools as Claude Code does. With an API key it does not.
- **Partial messages:** Pi's stream repeats a tool's arguments with each piece of the tool's output
  (`tool_execution_update`), and sends each piece of a streamed message (`message_update`; Pi 0.74.2 repeated the
  whole message so far with each piece, and a 100 KB file written in 50-character pieces gave 404 MB). Nothing reads
  those events: a fixed filter in the container drops them before Traceworks reads the stream,
  and ends with Pi's exit code; when grep fails it ends with grep's own, and when it cannot keep Pi's exit code it
  says so on stderr and ends with 74. With Pi 1.5.2 a 5 MB file written in one call, its arguments streamed in
  50-character pieces, ran within 1 GiB of container memory.
- **The outcome:** the answer is the text of the last assistant message. Tokens and cost are summed over the call's
  replies as the stream reports them, the cost at Pi's own prices; only a stream without tokens is estimated
  (characters / 4). No assistant message, a last assistant message that stopped
  with an error (Pi then still ends with 0) or for a tool call no reply followed (`toolUse`), a stream that does not
  end Pi's run (an `agent_end` that announces no retry), and a non-zero exit code fail the call; its error is Pi's message, or the end of the
  stream, and then the end of Pi's stderr.

## Model choices

A workflow names the model of each AI step: a fixed `provider` and `model` is exactly that, and nobody changes it at
the start of a run or with a setting. A workflow can also open the choice. It declares, at its top, a choice with a
name, a default and the options, each a provider and model pair, and a block refers to the choice with
`model_choice` instead of naming `provider` and `model`:

```json
{
  "requires": ["ai.model_choice"],
  "model_choices": {
    "judge": {
      "default": "fable",
      "options": {
        "fable": {"provider": "CLAUDE", "model": "claude-fable-5-2"},
        "opus":  {"provider": "CLAUDE", "model": "claude-opus-5-5"},
        "astra": {"provider": "PI", "model": "openai/gpt-6-astra"}
      }
    }
  },
  "blocks": [
    {"type": "simple", "name": "verdict", "config": {"command": "Judge it.", "model_choice": "judge"}}
  ]
}
```

```bash
traceworks run <package>                        # the default: fable
traceworks run <package> --input judge=astra    # the option astra: PI with openai/gpt-6-astra
```

- **Who picks.** Whoever starts the run, with the input named like the choice (`--input`, or the inputs of an
  earlier run with `--inputs-from`). Without the input the default holds. A value that is none of the options
  refuses the run before it exists (exit 6), alone and with the options listed: `the model choice "judge" has no
  option "gpt": the input judge picks one of fable, opus, astra (the default is fable)`.
- **When.** Before the first step, once. The pick is read from the inputs the run starts with and from nothing
  else: not from the output of a step, a file of the workspace or a variable a step sets. The definition a run runs
  has the picked provider and model in place of every `model_choice`, so a step reads them as if they were written
  there.
- **What the run knows.** Three variables per choice: the choice's name (the picked option, `{{ judge }}`),
  `{{ judge_provider }}` and `{{ judge_model }}`. They are set after the inputs, and nothing replaces them: an input
  or a `default_context` entry named like the provider or the model variable refuses the run, and no block may set
  one of the three. A block that would is refused by `check` and by the run admission (`invalid-model-choice`): the
  `variable_name` of a `foreach`, an `approval` or a `human-input`, the `variable_name` of a `human-input`'s
  `fields`, the `<variable_name>_index` a `foreach` sets, and the variable a block sets when it names none (`item`,
  `approval_answer`, `user_input`). So a report step can name who judged with them. A `context` of a `foreach`
  that names one is handed to the child run, which sets its own three after it.
- **The declaration.** `model_choices` maps the name of a choice to `default` and `options`; an option has exactly
  `provider` and `model`, both a literal text. The name of a choice is a plain name (letters, digits and `_`) and
  not one a run sets itself (`model`, `provider`, `previous_output`, ...); `default_context` may not set it, since the
  default stands in the declaration. Two choices may not share a variable: next to a choice `x`, a choice named
  `x_model` or `x_provider` is refused. A `model_choices` that is no object fails the load of the package, with a
  message that names the key. A block has `model_choice` or its own `provider` and `model`, not both. The
  declaration needs `ai.model_choice` in `requires`. `traceworks check` reports a declaration or a reference that
  cannot be used (`invalid-model-choice`), an option with an unknown provider (`unknown-provider`) or, for Pi, an
  unknown backend (`unknown-model-backend`), and a choice no block refers to (`unused-model-choice`). Web on a step
  that refers to a choice with a Pi option is refused (`unsupported-web`): a run could pick it.
- **The start check** uses the picked option: the image, the credential (for Pi: of the picked model's backend)
  and the hosts of its provider, and nothing of an option that was not picked. A run with the option `fable` above
  needs no `pi` image and no `OPENAI_API_KEY`. `traceworks images build <package>` builds the images of every
  option, since it does not know which one a run will pick. A run reads and redacts the credentials of the provider
  it picked; a credential of an option that was not picked reaches no container of the run.
- **A child workflow** (`sub-workflow`, `foreach` with `workflow_id`) with a choice of its own is picked by the inputs
  of the run that started first, by the choice's name: `--input judge=astra` picks `judge` in the run's workflow and
  in every child that declares a choice of that name; a child's choice the inputs do not name runs its default. The
  `default_context` of the run's workflow counts as an input of the run here: a root workflow's `default_context` may
  not name its own choice, but an entry named like a child's choice picks that child's option, since the workflow that
  starts the child decides for it. What a parent's steps set does not pick, nor does the `context` a `foreach` hands
  to its child: a child gets its parent's variables, and a step can set those. A child's pick stands in no
  `run.started`, because a child run has none: its `ai.request` lines name the provider and the model of each call. The start check judges the children it reaches by a
  literal `workflow_id`; a child named by a placeholder is judged when its step starts it, and a refused pick fails
  that step.
- **The record.** `run.started` has `model_choices`: per choice the picked option, whether it is the default, and
  its provider and model (docs/events.md). Every call's `ai.request` names the provider and the model it ran with,
  as for any AI step. `show` and `run.html` name the pick, and `diff` of two runs with different picks says so:
  `the inputs differ` and `the model choice judge differs: ...`.
- **A resumed run keeps its pick.** A resume picks the option `run.started` recorded for each choice, whatever
  the inputs or a default say by then, and its start check asks for that option's image and credential.
  `run.resumed` names the picks it goes on with (`model_choices`). With `--allow-changed-package` a resume runs the
  definition recorded at the start, so the declaration of the run's own workflow is the one it started with; a
  recorded option that the definition it runs does not declare refuses the resume. A child workflow is read from its
  package when its step starts it, at a resume too: its pick is recorded nowhere, and a child package that changed
  its default meanwhile runs the new one.
- **A seal** covers the declaration, since it is part of `workflow.json`: the default and the list of options. A
  pick within the list does not touch the package, so it breaks no seal; a caller cannot add an option or a model.
- **An option may be a local model.** An option with the provider `PI` and a model `<backend>/<model>` of a model
  server the operator declared runs on that server ("Local models"); the start check then asks for that server's
  secret, if it has one, and for no credential of a provider, when the run picks it.
- **The operator narrows, and never widens.** `allowed_models` in `traceworks.json` lists the provider and model
  pairs that may run in a home ("traceworks.json"). A run with an AI step whose provider and model no entry allows
  is refused before it starts, whether the workflow names the model or the run picked it: `the model
  openai/gpt-6-astra of PI may not run in this home: allowed_models in traceworks.json allows CLAUDE claude-*`. A
  step that names no model runs its provider's default, which Traceworks does not know: with the setting, only an
  entry without `model` allows it. The setting holds for child workflows and for a resume, with the value it has at
  the resume. Without the setting every model may run, as before it existed.

## Locale

Traceworks hands a command its arguments and environment in the encoding of its own locale. Under a locale that is
not UTF-8, such as `LANG=C`, a character beyond ASCII would reach the command changed, so a command whose arguments
or environment hold one is refused before it starts: a container command ends with exit code 125, a host command
with exit code 1, and the message names the variable, never its value. Run Traceworks with a UTF-8 locale, for
example `LANG=C.UTF-8`. What a command reads on stdin, a step's shell command and an AI call's prompt among it, is
UTF-8 under any locale, and the system prompt travels base64-encoded. The names of the files in the scratch directory
are recorded as Java decodes them under the locale (`step.files`, docs/events.md), so under a UTF-8 locale a name
reads as the step wrote it.

## Requirements

A workflow names the features beyond the common core it needs in `requires`, at its top:

```json
{"id": "...", "name": "Review", "requires": ["ai.tools"], "blocks": [...]}
```

| Requirement | Needed by |
|---|---|
| `container.network` | `network: true` on a step that runs a shell command or a tool (a `shell` block, a wrapper in shell mode, a shell block of a `switch` case) |
| `package.images` | a `container_image` `./images/<name>`, one of the package's own images, on such a step |
| `ai.tools` | a `tools` value other than `none` on a `simple` or `agentic` block, or on a wrapper for its AI step |
| `ai.web` | a `web` value other than `none` on one of the same blocks ("Web tools of an AI step"). It is separate from `ai.tools`: `tools: none` with `web: search` needs only `ai.web` |
| `ai.model_choice` | a `model_choices` declaration at the top of the workflow ("Model choices") |

A workflow that uses one of them without naming it is refused before its first block, and `traceworks check`
reports `missing-requirement` at the first block that needs it. A requirement that no block uses is the warning
`unused-requirement`. Any other entry of `requires` is refused (`unknown-requirement`).

A run resolves `./images/<name>` against the package it was started with. Every child workflow (a `sub-workflow` or
`foreach` with `workflow_id`, by path or by id) is a package of its own, also one in a subdirectory of the run's
package, since a directory holds one `workflow.json`. A child that names `./images/<name>` is refused before its
first block, since its step would run the run's image of that name, not its own package's; `traceworks check`
reports `child-package-image` at the block that runs the child. Give such an image a registry name, or run the
child's workflow as a run of its own.

## Actor

Every run, answer, resume and cancel is attributed to an actor. It is, in this order:

1. the environment variable `TRACEWORKS_ACTOR`
2. `actor` in `traceworks.json`
3. `user@host`

The actor is only a label for the record. Traceworks does not authenticate it.

An AI agent that works with your user has your actor. For a decision in a register and for the answer to a
question of a run it can name itself beside the actor (`--agent NAME` or `TRACEWORKS_AGENT`; "findings", "approve
and answer"); for the start of a run, a resume, a pause and a cancel there is no such name, and the record names
the actor alone. The name is as little authenticated as the actor.

## Output limit

Traceworks keeps at most the output limit of what a command prints on stdout, and as much of what it prints on
stderr; on the host, where both are one stream, the limit of that stream. The limit is 16 MiB unless the operator sets
`output_limit_mib` in `traceworks.json` (from 1 to 1024 MiB; see "traceworks.json"). It is the operator's alone: no
workflow can set it, so a package cannot raise its own limit. In a block's `config` the key has no effect, and
`traceworks check` warns about it (`unknown-config-key`); at the top of a workflow it is an unknown field, and the
package does not load. Every command of a run has the same limit, wherever it runs: a step's command and its tool
mode, the inner shell steps of the wrappers, a `run_if` test, a shell condition, an `items_command` and an AI CLI's
call; so do the calls of `traceworks images build`. So does the output of the git calls that check a `--source` out: a call that prints more is stopped and the
run is refused (exit 6). `run.started` records the limit a run starts with as
`output_limit_mib`, and `run.resumed` the one a resume runs with: the setting in force at that moment
(docs/events.md). Traceworks holds what it keeps in memory: a higher limit needs a heap to match, and the run's output
budget bounds what a run holds in all (see "Output budget").

A stream that goes beyond the limit stops the command at once, as a timeout does: the command and what it started get
SIGTERM (each process before its children, so a shell does not go on to its next command because the one in flight
ended; one whose own TERM trap does not exit does go on), what is left 2 seconds later SIGKILL in the same order,
and its container is removed. The command has failed, whatever its own
exit code would have been, and what ran it never reads the cut text as if it were whole:

- a `shell` step, also the inner shell step of `conditional`, `retry`, `switch`, `while` and `foreach`, fails with
  exit code 153. Its output is the first part of the stream up to the limit, cut where no UTF-8 character is split and
  before the first characters of a secret value the stream ends with, which the record could not redact
  (docs/events.md, "Redaction"), then a line that names the limit in effect, such as
  `The command's standard output exceeded 16 MiB; Traceworks stopped it and kept the first 16 MiB`;
- a tool step (`container_args`) fails the same way; one with SARIF output counts as not measured;
- a `run_if` test, a shell condition of `conditional` or `while` and an `items_command` fail their block, with that
  line in its output, instead of reading an exit code or a list that the stop cut short;
- an `items_command` that runs out of time (the block's `timeout_seconds`) fails its `foreach` the same way, before the
  first item, with the output `items_command failed: it timed out after <n> seconds`; the line that reports the
  timeout is never an item. Any other non-zero exit of an `items_command` is not a failure: its output lines are the
  items, as before;
- a `run_if` test, or the shell condition of a `conditional` or `while`, that runs out of time (the test's fixed 60
  seconds, or the block's `timeout_seconds`) fails its block too, instead of counting as a false test: no skip, no
  `else` branch and no end of the loop. The output is `run_if failed: it timed out after <n> seconds` or `The
  condition failed: it timed out after <n> seconds`; a `while` keeps the iterations it had run. The runner's timeout
  mark decides, not the exit code: a test or condition that exits 124 by itself is an ordinary false one, and so is
  any other non-zero exit;
- a `run_if` test, a shell condition of `conditional` or `while` and an `items_command` that did not run fail their
  block the same way: no skip, no `else` branch, no end of the loop and no item. A command did not run when
  Traceworks refused it before it started (a missing image, no container runtime: exit 125), when its process or
  container client could not start (exit 1), when it was cancelled before its container started (exit 130), or when
  its script did not arrive whole in bash (exit 74, with the line `traceworks: the command did not arrive whole on
  stdin`). The output is `run_if failed: it did not run: <reason>`, `The condition failed: it did not run: <reason>`
  or `items_command failed: it did not run: <reason>`, such as `run_if failed: it did not run: image
  traceworks/shell:<hash> is not present; run traceworks images build`. The runner's mark decides, not the exit code:
  a test that exits 125, 1 or 74 by itself is an ordinary false one. Two ends cannot be told from the command's own
  exit, so they count as its answer: a container that `docker run` could not start (the client's own 125), and a
  kill at the memory limit (137), which only the runtime knows of and the container's removal forgets. A
  `checked_command` step that did not run fails RED with the description `Command did not run (exit <n>)`;
- an AI call fails with that line in its error, whatever its CLI's cut stream held; its tool calls are those of the
  lines that ended before the cut;
- the output of such a step sets no gate: its `gate_status:` lines are those printed before the cut, and one printed
  after it, a worse one say, is lost. The step carries `gateMarkers: false` in its metadata, and so does a wrapper
  whose output holds such an inner step's output. A `checked_command` step keeps the gate of its exit code: RED, with
  its `check_category` and the description `Command exit 153`.

`step.command.finished` names the stream in `truncated` (docs/events.md). Up to the limit nothing changes. A command
that has more to hand on writes it to a file in the scratch directory (`{{ scratch_dir }}`) and prints only what later
steps need.

### Output budget

A run holds in memory the output text it keeps beyond the step that produced it: the input and the output of every
finished top-level step, what a `foreach`, a `while` or a `switch` has gathered so far, the item list of an
`items_command` while its `foreach` runs (given back when the loop ends), and the current values of the
variables its steps set, such as a `human-input` answer or a `foreach` `context` mapping that copies a block output
(a new value replaces the old one's count). The run's inputs and run variables do not count. A child run's steps and
variables count while it runs and are given back when it ends. The copies of an output that `{{ previous_output }}`,
`{{ step_N_output }}` and a top-level block's `{{ block.<name>.output }}` resolve to are the same text and do not count
again. The output of a named `switch` case block is held twice, in `{{ block.<name>.output }}` and in the switch's
joined output, and counts once: that is bounded by the number of case blocks in the definition.

The run's output budget bounds that text, counted as the memory it takes: a byte per character for a text whose
characters are all at most U+00FF, and two bytes per character for any other text, since one character such as `✓`
makes Java hold the whole string at two bytes per character. It is `run_output_budget_mib` in `traceworks.json`, from 1
to 65536 MiB, by default 128 MiB, or eight times `output_limit_mib` when that is more, so that a higher output limit
does not make steps fail at the budget. A value below twice `output_limit_mib` is refused when the home is read (exit
6), since one step can hold its stdout and its stderr up to the limit each. Like the output limit it is the operator's
alone, and no workflow can set it. `run.started` records the budget a run starts with as `run_output_budget_mib`, the
default as a number too, and `run.resumed` the one in force when it resumes (docs/events.md). A resumed run counts the
texts of its checkpoint and the variables its steps set again. A resume whose checkpoint already holds more than the
budget in force is refused before anything is recorded (exit 6), with a message that names what the run holds and the
budget: a parked answer stays for a resume with a larger `run_output_budget_mib`.

The step whose text would take the run beyond the budget fails, and the run ends there:

- a step keeps its whole output, followed by a line that names the budget in effect, such as
  `The output this run holds in memory would exceed its budget of 128 MiB; Traceworks failed this step and ended the run`,
  and carries `outputBudgetExceeded: true` in its metadata. That flag, not the line, says that the budget failed it: a
  step that prints the same line itself is no such step;
- a `foreach`, `while` or `switch` stops at the item, iteration or case block whose output would not fit, and a
  `foreach` before an item whose variables would not fit, or before its first item when the list of its
  `items_command` does not fit (its output is then that line alone). That output stays out of the step's output (a `foreach`
  item's `foreach.item.finished` records it whole); the step's output is what it gathered before, followed by that
  line, and it sets no gate (`gateMarkers: false`). The loop's own lines, which say how it ended (its
  `[continue_on_error]` or `[max_consecutive_failures]` tally, a `[condition]` that failed), stay in its output
  whether or not they fit; one longer than 2000 characters is cut there and says so at its end;
- the run ends `FAILED` (exit 3) with the error `Step N (type) failed: ` and that line (after a `foreach`'s summary of
  its failed items, when it has one), whatever the step's `on_failure` or a `foreach`'s `continue_on_error` says,
  since each further step would only hold more. A child run that ends this way ends the run above it too, and a child
  run that starts beyond the budget, through a value its parent set, starts no block.

No step gets a cut text as if it were whole. What a step copies for a moment while it runs and records its output does
not count, so a run needs a heap of about three times its budget (docs/security.md, "Output").

## Host execution

A run is a **container run** wherever `podman` or `docker` works. Every command runs in a container: a `shell` step
without `container_image`, and `run_if`, a shell condition or `items_command`, run in the built-in `shell` image. The
workspace is mounted read-only at `/workspace` (writable with `workspace_writable: true`), the package read-only at
`/package` and the run's scratch directory writable at `/scratch`; the command starts in `/workspace`. Steps hand
files to each other through the scratch directory, which the run variable `{{ scratch_dir }}` names. The run of an
audit workflow has one more mount, read-only at `/register`, for every container but that of an AI step: the files
with what its register knew (`{{ register_dir }}`; docs/registers.md, "The register folder of a run").

The package stays read-only also where a writable workspace holds it: the container then sees the package once more,
read-only, at its place in `/workspace`. A writable workspace that is the package or lies inside it is refused.

`workspace_writable: true` works on a `shell` block (its tool mode included), on `simple` and `agentic`, on
`conditional`, `retry`, `while` and `foreach` for their inner shell or AI steps, and on the shell and `simple`
blocks of a `switch` case. Every other container sees the workspace read-only. A container that may write the
workspace still sees the workspace's own top-level `.git` read-only, unless you set `workspace_git_writable` in
`traceworks.json` or the workspace is the checkout of `--source` ("The `.git` of a writable workspace").

A **host run** is the fallback for a machine where no container runtime works, and it needs your consent, with
`--allow-host-execution` or with `allow_host_execution: true` in `traceworks.json`. The consent only allows a host run:
where the container runtime works, the run is a container run whatever the consent, and without a runtime and without
the consent it is refused (exit 6). The runtime works when it answers `version` within 10 seconds: the configured
`container_runtime`, or without one the first of `docker` and `podman` on `PATH`. A daemon that answers too slowly,
or a socket you may not use, counts as no runtime, so with the consent such a run goes to the host; its first line
says so.

In a host run a shell command without an image runs on the host with `bash`, and can do anything you can do. A step
with `container_image`, and every AI step, needs a container, so it fails in a host run that has no runtime; only a
host run that resumes where a runtime works now runs such steps, in containers that see each directory at its host
path. A workspace that is the package is one directory there: it is mounted once, read-only.

The first line of `run` and `resume` says where the run runs: `in containers (docker 29.1.3)`, `on the host (no
container runtime works)`, or, for a host run that resumes where a runtime works now, `on the host, where it started`.

Before a run is created, the start check refuses it (exit 6), with every reason in one message:

- a container run without a container runtime (neither `podman` nor `docker` answers `version`, or the configured
  `container_runtime` does not);
- an image the run needs that is not there, with the command that provides it: the `shell` image (in a container run),
  every literal `container_image` of the workflow and its nested blocks, the image of every AI provider it uses,
  in its literal child workflows too, and with any of them the `egress` image, unless `ai_network` is `open`. `traceworks images build` builds the built-in images, `traceworks images build
  <package>` a package's images and the registry images it names. A run never builds or pulls. For a CLI that
  `ai_images` replaces, the check asks for the operator's image, with the hint that it is set in `ai_images` and is
  the operator's to build or pull;
- a `./images/<name>` in a literal child workflow of another package (see "Requirements");
- a workspace or package that a container must not see: `/`, the home directory or a folder holding it, the
  Traceworks home or a folder holding it, its run records, ledger, registers, seal log and caches (`<home>/runs`,
  `<home>/ledger`, `<home>/registers`, `<home>/seals`, `<home>/cache`, the folders that `runs_dir`, `ledger_dir` and `registers_dir`
  name, and everything in them or holding them), a login below the home (`~/.ssh`, ...), the paths a step's own `mounts`
  may not name (`/etc`, `/proc`, `/sys`, `/dev`, `/run/user` and below, the runtimes' sockets and storage, your session's
  sockets), a directory that does not exist or directly holds a socket, a named pipe or a device node, and a path
  with a comma, a quote or a line break. A folder in `/dev/shm` may be a workspace or a package (a memory disk for a
  build); `/dev/shm` itself and the rest of `/dev` may not. The check reads the real path, with symbolic links resolved;
- a workspace that is the package or lies inside it, when a step of the run (of a literal child workflow too) sets
  `workspace_writable: true`: the step would change the package, which every step only reads.

`traceworks resume` keeps the run's target. A host run resumes on the host, also where a runtime works now, and needs
the same consent to resume, with the refusal "Host execution is not allowed": a run folder copied to another machine
does not run commands on that host unless its operator allows it. A container run needs no consent, only a container
runtime.

The record says where a run ran: `run.started` has `execution` (`container` or `host`) and `container_runtime`
(`{name, version}`, or `null` for a host run that found none), every `step.command` has `host`, `image` and
`image_id`, and `manifest.json` lists the images under `images`.

A command on the host does not get your environment. It gets only `PATH`, `HOME`, `LANG`, the thirteen locale
categories (`LC_ALL`, `LC_CTYPE`, `LC_MESSAGES`, `LC_TIME`, `LC_NUMERIC`, `LC_COLLATE`, `LC_MONETARY`,
`LC_PAPER`, `LC_NAME`, `LC_ADDRESS`, `LC_TELEPHONE`, `LC_MEASUREMENT` and `LC_IDENTIFICATION`; no other `LC_`
name), `TERM`, `TMPDIR`, `USER`, `LOGNAME`, `SHELL` and `TZ`, plus what the step sets itself. API keys and tokens
stay out.

**A process the command leaves behind.** A command on the host can end while something it started still writes to
its output: a background job (`server &`), or a process substitution, which bash does not wait for
(`exec > >(tee log)`). The step's output holds what such a process writes until it closes the output, for a bounded
time: up to 5 seconds after a command that ended by itself, up to 2 seconds after one that was stopped (a
timeout, an output beyond the limit, a cancel). The step takes that much longer. A process that still holds the output then
is not ended: the output is closed, so its next write fails (it gets SIGPIPE), and what it would have written is not
in the record. The record marks that: the command's `step.command.finished` has `output_cut` with the stream and
the bound (`{"streams":["stdout"],"after_ms":5000}`, docs/events.md), and only then; a command whose output ended
has no such field. The step's status and the gate are as they would be. `traceworks show` prints
`output CUT     stdout after 5000 ms   <== may be incomplete` under the step and counts such commands in a line
`Output`, `run.html` marks the step and the command, and `traceworks diff` takes a cut in one run and none in the
other as a difference ("diff"). A process that holds the output and never writes is marked the same way, at the
bound; the record holds what came before. A wait that was interrupted (exit code 130) ends the reading at once and is
marked with `"after_ms":0`. A record of an earlier version has no mark for any of this, and its silence says
nothing: only a record whose `run.started` names `output_cut` in `records` can say (docs/events.md). Let the command wait for what it starts (`wait`) when its output matters, and send
the output of a server it leaves running to a file.

Reading past the command's end rests on how the JDK handles the output of a process on Linux and macOS (tested with
a JDK 21). On a JDK or a platform that handles it differently, the output ends with the command: what a process it
left behind writes afterwards is lost. A test of Traceworks fails when that changes.

In a container there is no such process: the container ends with its command, and everything the command started
ends with it. What Traceworks reads there is the output of the client (`docker run`, `podman run`), which alone
holds those pipes; the processes in the container write to the runtime's. So a container step is not cut this way.
The client's output is still read with a bound, 2 seconds after the client's end, and a client that left something
behind which holds its output would be marked the same way.

## Isolated workspace

`traceworks run <package> --source <path|url> [--ref <ref>]` runs the package in a new checkout of a git repository,
not in a workspace of your choosing. You choose it when you start the run; a workflow cannot ask for it (one
with `workspace.mode: isolated` is refused, docs/workflow-format.md). `--source` and `--workspace` exclude each other, and `--ref` needs `--source`;
either is a usage error (exit 2).

Before the run is created, and after the start check, the source is cloned into `<home>/workspaces/<run-id>/`
(`git clone --no-checkout --no-hardlinks --no-recurse-submodules`, with `--local` for a path) and checked out at one
commit, detached (`git checkout --detach <sha>`); each git call may take ten minutes. That checkout is the run's
workspace: a container sees it at `/workspace`, read-only unless a block sets `workspace_writable: true`, and then
the step's writes stay in the checkout, never in the source. A step's own `mounts` entry inside the checkout follows
the rules of a mount inside a workspace: `:ro` always, `:rw` only with `workspace_writable: true`, and then without
an entry in `writable_mounts`, as in any workspace the step may write. The `.git` of the checkout is writable with
the rest of it, without `workspace_git_writable` ("The `.git` of a writable workspace"): a step commits there. The
checkout is a run's data and no repository of yours, and Traceworks runs no git in it after a step ran in it; a
checkout that is kept after the run holds what its steps left, so read it with care before you run git in it.
Another run's checkout cannot be mounted.

`run.started` records the source as `workspace_source` and the full commit as `workspace_source_sha` (see
docs/events.md). The steps see the commit as the run variable `{{ workspace_source_sha }}`, which is empty in a run
without `--source`; no input sets it.

**Sources.** A source is one of:

- a local path; a relative one counts from the working directory, and the record has it as an absolute path;
- an `https://`, `ssh://` or `file://` URL; a `file://` URL with a `?` or a `#` is refused, since git reads them as
  part of the path (write `%3F` or `%23`, or give the path itself), and so is one with a host other than
  `localhost` or with a `%` or a bracket before its path (write `file:///<path>`);
- the scp-like `user@host:path`. Without a user, write `ssh://host/path`.

Anything else is refused (exit 6) before git runs: plain `http://` and `git://`, since a source is fetched with your
credentials and an attacker on the way could change the commits before their sha is recorded; a remote helper
(`ext::`, `fd::`, any `<helper>::`), since it runs a program; and a source that starts with `-`, or whose host does
(also in brackets). A source or a ref never becomes an option of git.

**Nothing in `workspaces/` is read by git on the host.** A checkout is a run's data: a step with
`workspace_writable: true` may have written its `.git/config` (a clean filter, a pager, an fsmonitor), and git on the
host would read it, `git status` for a package's git state and `git-upload-pack` for a source, which a clone runs in
the source without Traceworks' options. So `run` and `resume` refuse (exit 6), before git runs, a package whose real
path lies in `<home>/workspaces/` and a local source (a path or a `file://` URL) whose real path lies there, also
through a symbolic link. Copy what you want to keep out of a checkout first. `check` and `images build` refuse such a
package as well. A package or a local source whose real path lies in `<home>/runs/`, or in the folder `runs_dir`
names, is refused in the same way:
every step writes its run's scratch directory there, so a workflow could name a folder in it as a child workflow, and
a repository in it is a step's work, with a `.git/config` the clone's `git-upload-pack` would read. A path that cannot be
resolved for another reason than that it does not exist (a link without a target, a link in a circle) is refused
too, since it cannot be told to lie outside.

**Refs.** `--ref` is a branch, a tag or a commit (a full or abbreviated sha). Without it, the source's `HEAD` is
checked out: for a URL its default branch. A ref is looked up as a revision first, then as a branch of the source
(`refs/remotes/origin/<ref>`); a ref that names no commit is refused (exit 6), and nothing is left in
`workspaces/`.

The clone writes no reflog, so no file of the checkout names the URL it was cloned from; its `.git/config` names
the source as the record does, without credentials.

**Nothing of the source runs.** git runs with the hooks and the fsmonitor switched off, without your init template,
with submodules not fetched (a submodule's directory stays empty) and the `ext` transport off, and with every
inherited `GIT_*` variable removed. No filter driver runs, not even one your own git configuration defines for an
attribute the source's `.gitattributes` names: before the checkout, `.git/info/attributes`, which beats the source's
own attributes, says `* -filter -ident -text -working-tree-encoding`. The checkout holds the commit's bytes, so
**a git-lfs file stays its pointer**, `$Id$` is not expanded, and no line ending is converted (neither by
`core.autocrlf` nor by an `eol` attribute). LFS content is not fetched.

**Output.** git's stdout and stderr are kept up to the output limit ("Output limit"), each. A call that prints more,
such as a remote that streams messages without end, is stopped, the checkout is removed and the run is refused (exit 6)
with a message that names the limit.

**Credentials.** git uses your own credential helper and SSH keys, and asks for no password on the terminal
(`GIT_TERMINAL_PROMPT=0`): a source that needs one and has none fails (exit 6). A user and a token in a URL work for
the clone, but they go nowhere else: the record names the URL without its user information, query and fragment
(`https://user:token@host/repo?x=1#y` is `https://host/repo`), the checkout's `remote.origin.url` is set to that, and
git's error output is shown with the URL replaced the same way. An `ssh://` URL keeps its user name, which is no
credential. Prefer a credential helper to a token in the URL: the URL is an argument of `git clone`, so every process
on the machine can read the token in the process list while the clone runs, and a resume that has to clone again has
only the recorded URL.

**Clean-up.** When the run ends completed, completed with errors, failed or cancelled, its checkout is removed,
without following a symbolic link in it (a link is removed, never what it points to), unless a command of the run
could write in it: a command on the host (every shell command without an image in a host run), or a container with a
writable mount of the checkout (`workspace_writable: true`). Such a checkout stays as it was left, and the summary
names it:

```
  kept    ~/.traceworks/workspaces/<run-id> (the checkout of the source: a command of the run could write in it)
```

Remove it when you no longer need it. A checkout that cannot be removed stays and is named the same way, and so does
the checkout of a run that a failure ended (its record could not be written, its manifest or ledger entry failed):
the error is followed by `traceworks: the checkout <path> of the run's source stays; ...`. A parked or interrupted
run keeps its checkout for its resume; `traceworks cancel` of a parked run removes it as at any other end. The
decision is taken from the record (`step.command` says where each command ran and how its mounts were mounted),
never from git in the checkout. Ctrl-C while the source is being cloned stops git and Traceworks without clean-up,
so a partial checkout may be left in `workspaces/`; no run exists for it, and you remove it yourself.

**Resume.** A resume works in the checkout as it is. Once a step has run there, Traceworks never runs git in the
checkout again: a step with `workspace_writable` can write `.git/config` (a clean filter, a pager, an fsmonitor), and
git on the host would run it. Only a checkout that is missing is cloned again, from the recorded `workspace_source`
at the recorded `workspace_source_sha`, and `run.resumed` records `workspace_cloned: true`; what a step had written
there is then gone. When that clone fails (the source cannot be reached, a token that was part of the URL is not
recorded, or the commit is no longer in the source), the resume is refused (exit 6) with the reason, and records
nothing. Two missing checkouts are never cloned again, and the resume is refused (exit 6) with a message that says to
start the run again with `--source`:

- one whose recorded source holds `[redacted:<NAME>]`, because a secret's value was part of the source. A resume
  does not put the value back into the URL of a clone: a record can be rewritten, in a run folder copied from
  elsewhere for example, and the value would go to whatever host the record then names;
- one of a run folder that is not in the runs folder of this home (opened by its path). Its record was not written here, and
  the clone would run git on the host against a source nobody on this machine chose.

A checkout that is still there is used in both cases, since nothing is cloned.

## Packages in a git repository

When a package is in a git repository, `run.started` records its commit and whether its directory has
uncommitted changes (`git_commit`, `git_dirty`). A package may come from somewhere you do not control, and git can
run programs that a repository's own configuration names. Traceworks therefore asks git with `core.fsmonitor`
and the hooks switched off, without writing the index (`--no-optional-locks`), with every filter driver
(`filter.<name>.clean`, `smudge` and `process`) emptied and not required, and with submodules ignored. A package
that uses filter drivers, for example git-lfs, may therefore read as dirty. A driver whose name cannot be handed
back to git exactly (one with `=`, or with anything but printable ASCII) is not emptied: git is then not asked for the state at all. Each of the three git calls (the commit, the listing
of the filter drivers, the status) keeps at most 16 MiB of git's output; one that prints more is ended. In all
these cases the run records neither `git_commit` nor `git_dirty`: the state reads as unknown, never as dirty.
