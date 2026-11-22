# The block types

A workflow is a list of blocks, and the type of a block says what its step does: run a command, ask a model, loop,
branch, ask a person, run another workflow or shorten a text. Traceworks has thirteen block types. This page describes
each one: what it does, every setting of its `config` with its default and its bounds, what it may touch, what it
leaves in the record, how it behaves at a pause, a resume and a failure, a small example, and what it does not do.

The rules of the file as a whole (`format`, `requires`, `metadata`, key names, what the run admission refuses) are in
[docs/workflow-format.md](workflow-format.md); the commands that run, check and describe a workflow are in
[docs/cli.md](cli.md); the events named below are described field by field in [docs/events.md](events.md).

![The thirteen block types in five groups. Executing: shell runs a command, simple asks a model once, agentic loops a model towards a goal; a command and a model call both run in a container, which a small box in their marks shows. Control flow: retry, conditional, while and foreach hold a command that runs as a shell line or asks a model, as their execution_mode says, and switch holds shell and simple blocks; foreach can also run a workflow per item. A person: approval and human-input wait for a person, or ask a model in the person's place. Composing: sub-workflow runs another workflow as one step. Condensing: compress shortens an earlier output by fixed rules in the Traceworks process, and compress-ai asks a model to condense it.](blocks.svg)

## Overview

| Type | Group | What its step does | Executor |
|---|---|---|---|
| `shell` | executing | runs one command in a container, or a tool image's own entrypoint | `ShellBlockExecutor` |
| `simple` | executing | asks a model once | `SimpleBlockExecutor` |
| `agentic` | executing | lets a model plan, act and observe in a loop towards a goal | `AgenticBlockExecutor` |
| `retry` | control flow | runs a command until it completes, a bounded number of times | `RetryBlockExecutor` |
| `conditional` | control flow | runs one of two commands by a condition | `ConditionalBlockExecutor` |
| `while` | control flow | runs a command while a condition holds | `WhileBlockExecutor` |
| `foreach` | control flow | runs a command, or a workflow, once per item | `ForEachBlockExecutor` |
| `switch` | control flow | runs the blocks of the case a value matches | `SwitchBlockExecutor` |
| `approval` | a person | asks a question that a person answers with one of its options | `ApprovalBlockExecutor` |
| `human-input` | a person | asks a person for a value or a form | `HumanInputBlockExecutor` |
| `sub-workflow` | composing | runs another workflow as one step | `SubWorkflowBlockExecutor` |
| `compress` | condensing | shortens an earlier output by fixed rules, without a model | `CompressBlockExecutor` |
| `compress-ai` | condensing | condenses an earlier output with one model call | `CompressAiBlockExecutor` |

The executors are in `traceworks-core`, package `traceworks.core.blocks`, and `CoreBlocks.registry` registers all of
them; the command line builds its engine with that registry. Each executor names its type in `supportedTypes()`, and
that is the one list of the types that exist: a workflow that names any other type is refused before its first step
(`unsupported-block-type`). A package composes these types; it cannot add one.

Who does the work, by type:

| Type | Runs a command, in a container | Calls a model, in a container | Waits for a person | Holds other blocks, a command or a workflow |
|---|---|---|---|---|
| `shell` | yes | | | |
| `simple` | | yes | | |
| `agentic` | | yes | with `require_approval: true` | |
| `retry`, `conditional`, `while`, `foreach` | with `execution_mode: shell` | with `execution_mode: ai` | | a command; `foreach` also a workflow |
| `switch` | through its `shell` case blocks | through its `simple` case blocks | | `shell` and `simple` blocks |
| `approval`, `human-input` | | with `actor: ai` or `shadow` | yes, unless `actor: ai` | |
| `sub-workflow` | | | | a workflow |
| `compress` | | | | |
| `compress-ai` | | yes | | |

Everything that executes runs in a container: a command in the `shell` image or the image its block names, and a
model call as its provider's CLI in the built-in image `claude` or `pi`, with a network that reaches only that
provider (docs/cli.md, "AI steps" and "The network of an AI step"). Only `compress` and the wait for a person do
their work in the Traceworks process, without a container. The one exception is a host run, the operator's fallback
where no container runtime works: there a shell line without an image runs on the host, and a model call still
needs a container (docs/cli.md, "Host execution").

## What a block is

A block is one entry of the workflow's `blocks` list: a `type`, a `name` and the settings of its type in `config`.

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "3f1d7b2a-6c4e-4a9b-8d0f-2e5c7a9b1d01",
  "name": "Greeting",
  "description": "Greets the name it is given, then says what it greeted.",
  "default_context": {"name": "world"},
  "blocks": [
    {"type": "shell", "name": "greet", "config": {"command": "set -euo pipefail; echo \"Hello, {{ name }}\""}},
    {"type": "shell", "name": "repeat", "config": {"command": "set -euo pipefail; echo The greeting was: {{ block.greet.output }}"}}
  ]
}
```

- `name` is unique in the workflow (`duplicate-block-name`). Other blocks refer to the block's output by it, so keep
  it to letters, digits, `_` and `-`.
- Every setting belongs in `config`. A field next to `type`, `name` and `config` is read by no run; `traceworks check`
  reports it (`unknown-block-field`, an error for a setting of the block's type such as `on_failure`).
- A setting `check` does not know for the type is the warning `unknown-config-key`, and a value of the wrong type or
  out of range is the error `invalid-config-value`: the run fails the block on it, whatever its `on_failure` says.
- A top-level block makes one step of the run. A block that holds commands or blocks (a loop, a `switch`) gives its
  inner steps step indices of their own, so the index of a later step runs ahead of the block's position in the
  list.

### How blocks pass values

| Placeholder or place | What it holds |
|---|---|
| `{{ block.<name>.output }}` | the output of the step of the block of that name, once it has run; also of a named block in a `switch` case |
| `{{ previous_output }}` | the output of the step before; inside a `while`, the output of the iteration before |
| `{{ step_N_output }}` | the output of step N, by step index |
| `{{ <input> }}` | an input of the run: `default_context`, overridden by `--input name=value` |
| a variable a block sets | `approval_answer`, `user_input` or the `variable_name` of a question; `item`, `item_index` and `item.<field>` inside a `foreach`; `agentic_goal_achieved` and `agentic_iterations` after an `agentic` step |
| `{{ package_dir }}`, `{{ scratch_dir }}`, `{{ project_path }}` | the package, the run's scratch directory, the workspace: `/package`, `/scratch` and `/workspace` in a container |
| a file in `{{ scratch_dir }}` | anything larger than a few lines; every step of the run sees the same directory, and `step.files` records what it holds |

A placeholder that names nothing stays in the text. In a prompt a value is inserted as text. In a shell line a value is
inserted as it is (in single quotes with `shell_escape_variables: true`), except a **protected** value: text a model
wrote, an untrusted input, platform data such as `{{ previous_report }}`, and every output of a run that carries
untrusted input. A shell line that names one is refused before it runs (`protected-value-in-shell`); the block hands
it to its command as a variable instead, with `values`:

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "3f1d7b2a-6c4e-4a9b-8d0f-2e5c7a9b1d02",
  "name": "Values",
  "description": "Hands the answer of a model to a command as a shell variable.",
  "blocks": [
    {"type": "simple", "name": "review", "config": {"command": "Say in one line whether a function named do_it is well named."}},
    {"type": "shell", "name": "keep", "config": {
      "values": {"REVIEW": "{{ block.review.output }}"},
      "command": "set -euo pipefail; printf '%s\\n' \"$REVIEW\" > {{ scratch_dir }}/review.txt"}}
  ]
}
```

docs/cli.md, "check", has the whole rule ("Values: how a protected value reaches a command"), and
docs/workflow-format.md the run variables.

### Settings of every block

| Key | Default | Meaning |
|---|---|---|
| `on_failure` | `stop` | `stop` ends the run `FAILED` when the step fails; `continue` goes on with the next block. Only `continue` (any case, spaces around it) goes on; any other value stops. In a `switch` case it says whether the case goes on with its next block |
| `run_if` | none | a shell test before a top-level block: exit 0 runs the block, any other exit skips it (`SKIPPED`, output `Skipped (run_if: exit <code>)`) without starting it. It runs in the run's `shell` image without network, for at most 60 seconds; one that times out, goes beyond the output limit or does not run fails the block. A block nested in a `switch` case does not read it |
| `values` | none | an object from a variable name (`[A-Z_][A-Z0-9_]*`) to a template; each template is filled in and set as an unexported shell variable before the block's shell lines run (`command`, `items_command`, `run_if`, a shell `condition`, the inner shell steps of a wrapper). A tool step (`container_args`) takes none |
| `untrusted_input` | `false` | the block reads content from outside: its model calls get a preamble that says such content is data, and the step's input and output are searched for instructions aimed at a model; a hit goes into the step's metadata (`untrustedInstructions`) and makes the run's gate YELLOW (category `untrusted-instruction`) |
| `shell_escape_variables` | `false` | single-quotes every placeholder value in the block's shell lines, `run_if` included |
| `validation` | none | checks a top-level block's output: `contains` (a text it must hold), `min_length` and `max_length` (0 to 2,147,483,647 characters). An output that fails fails the step with `[VALIDATION] ...` after it; with `retry_on_fail: true` the whole block runs again, up to `max_retries` times (0 to 100, default 1), and the step's metadata counts the attempts (`validationAttempts`, `validationFailures`) |
| `description` | none | a note on the step, kept in the step's metadata as `blockDescription` for the run view |
| `label` | none | not read by the run |
| `workspace` | none | only `{"mode": "isolated"}` is read, and refused: the operator isolates a run with `run --source` |

## Executing

### shell

Runs one command and records its exit code and its output. The command reaches bash on stdin, in the run's built-in
`shell` image (bash, coreutils, git, python3, jq) or in the image `container_image` names, and starts in the
workspace. With `container_args` instead of `command`, the step is a tool step: the image's own entrypoint runs with
those arguments, without a shell.

| Key | Default | Bounds | Meaning |
|---|---|---|---|
| `command` | | required unless `container_args` | the shell line |
| `container_image` | the run's `shell` image | | a registry image, or a package image `./images/<name>` (needs `package.images` in `requires`); required for a tool step |
| `timeout_seconds` | 300 | 1 to 86,400 | a command stopped for it ends with exit code 124 |
| `network` | `false` | | `true` gives the container the runtime's network; needs `container.network` in `requires` |
| `workspace_writable` | `false` | | `true` mounts the workspace writable; its top-level `.git` stays read-only unless the operator sets `workspace_git_writable` |
| `mounts` | none | | more mounts, each `host:container:ro` or `host:container:rw`; the mode is required, and a writable one must lie in a folder of the operator's `writable_mounts` |
| `secrets` | none | names `[A-Z_][A-Z0-9_]*` | the secrets that reach the command as environment variables, from `credentials.json` or the environment |
| `values` | none | | see "Settings of every block" |
| `shell_escape_variables` | `false` | | see "Settings of every block" |
| `checked_command` | `false` | | runs the command under `set -e -o pipefail` and puts a gate derived from the exit code in front of its output: `gate_status: GREEN` for 0, `RED` otherwise, unless the command printed a worse verdict of its own |
| `check_category` | `build-check` | | the category of that gate; any character other than a letter, a digit, `_` or `-` becomes `-` |
| `container_args` | none | a list of texts | tool mode: the arguments of the image's entrypoint, one each, not interpreted |
| `output_format` | `raw` | `raw`, `sarif` | tool mode: `sarif` turns the SARIF files the tool writes into a fact sheet |
| `output_dir` | `/out` | | tool mode with SARIF: where the tool writes its files |
| `fact_prefix` | the block's name | | tool mode with SARIF: the prefix of the `FACT` and `HIT` lines |
| `cache_dir` | none | | tool mode: where a persistent cache folder of the image appears in the container |
| `mount_docker_socket` | | | refused: `true` fails the step, and `check` reports `docker-socket-refused` |

**What it may touch.** Its container sees the workspace at `/workspace` (read-only unless `workspace_writable`), the
package read-only at `/package`, the scratch directory writable at `/scratch`, and its own `mounts`. It has no network
unless `network: true`, and only the secrets it names. It runs as the owner of the Traceworks home, with a read-only
root file system, a writable `/tmp`, no capabilities and the operator's limits of memory, processes and CPUs. In a
host run (where no container runtime works and the operator consents), a step without `container_image` runs on the
host and its `mounts` are ignored; a tool step always needs a container runtime.

**What it leaves in the record.** `step.started` (`mode: deterministic`), `step.command` (the resolved command, the
image and its ID, every mount, the network, the names of its environment variables, and the `values` blob),
`step.command.finished` (exit code, duration, `timed_out`, `truncated`, `output_cut`), `step.output`, a
`gate.reported` when the output holds a `gate_status:` line, `step.finished` with `exitCode`, `checkedCommand` and
`executionMode` (`shell`, `container:<image>` or, for a tool step, `container-tool:<image>` with `outputFormat` and,
with SARIF, `factPrefix`, `measured` and `findings`) in its metadata, and `step.files` when the scratch directory
changed. The output is standard output and standard error together; after a non-zero exit a line
`[RECOVERY] <hint>` follows it, which says what the exit code usually means.

**Pause, resume, failure.** A pause lets the command run to its end; the run parks after the step. A cancel kills the
command, and the step ends `CANCELLED`. After an interruption (the process was killed), `resume` runs the step again.
A non-zero exit fails the step, and `on_failure` decides: with `continue`, a failed `checked_command` makes the run
end `COMPLETED_WITH_ERRORS` (exit 1), any other failed step leaves it `COMPLETED`.

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "7d3c6a52-1f0e-4f43-9a51-2c1b0c7e9a01",
  "name": "Shell block",
  "description": "Counts the files of the workspace and turns the exit code of a check into a gate.",
  "blocks": [
    {"type": "shell", "name": "count", "config": {
      "command": "set -euo pipefail; find . -maxdepth 1 -type f | wc -l",
      "timeout_seconds": 60}},
    {"type": "shell", "name": "has-files", "config": {
      "values": {"COUNT": "{{ block.count.output }}"},
      "command": "set -euo pipefail; test \"$COUNT\" -gt 0",
      "checked_command": true,
      "check_category": "files"}}
  ]
}
```

The second step's output starts with `gate_status: GREEN`, `gate_category: files` and `gate_description: Command
exit 0`, and the run ends with that gate.

**Limits and refusals.** One command per block, without a terminal and without input. A block takes `command` or
`container_args`, not both; a tool step without `container_image` is refused, and so is a tool step nested in a
`switch` case, which reads `command` alone. A mount without a mode, a mount of the Docker socket or of a login, and a
protected value in a shell line, a tool argument or a mount are refused before anything runs. A stream beyond the
output limit (16 MiB unless the operator sets another) stops the command and fails the step with exit code 153.
docs/cli.md has the details: "Tool mode of the shell block", "Secrets", "Writable mounts", "Output limit" and "Host
execution".

### simple

Asks a model once: `command` is the prompt, the answer is the step's output. The model only answers unless the block
gives it tools.

| Key | Default | Bounds | Meaning |
|---|---|---|---|
| `command` | | required | the prompt; placeholders are filled in as text |
| `provider` | the workflow's, else `CLAUDE` | `CLAUDE`, `PI` | the CLI that runs the call; a workflow that names Codex is refused |
| `model` | the workflow's, else the provider's default | no placeholder | the model, such as `sonnet`, or `<backend>/<model>` for a model server of the operator's (`PI`) |
| `model_choice` | none | a declared choice | the step takes the provider and the model of the option the run picked (docs/cli.md, "Model choices") |
| `effort` | the provider's | `low`, `medium`, `high`, `xhigh` | the reasoning effort |
| `tools` | `none` | `none`, `read`, `write`, `shell` | what the model may do besides answering: read and search files, also change them, also run commands; any value but `none` needs `ai.tools` in `requires` |
| `web` | `none` | `none`, `search`, `fetch` | the provider's web search, and with `fetch` the fetch of pages from `web_hosts`; needs `ai.web`, is refused on `PI`, and makes the step run as with `untrusted_input: true` |
| `web_hosts` | none | | the hosts `fetch` may reach, or `["*"]` for any public host on port 443 |
| `workspace_writable` | `false` | | the call's container may write the workspace |
| `timeout_seconds` | 300 (the provider's) | 1 to 86,400 | how long the call may take |
| `untrusted_input` | `false` | | see "Settings of every block" |
| `mcp_servers` | `[]` | only `[]` | an AI step reaches no MCP server; `user_mcp_servers: true` is refused |

**What it may touch.** The CLI runs in its provider's image with the workspace read-only (unless
`workspace_writable`), the package and the scratch directory, and a network that reaches the provider's hosts and the
operator's `ai_hosts` through an allowlist proxy, nothing else. It gets the provider's credentials and no secret of
the workflow. With `write` or `shell` and a read-only workspace, the model can change only `/tmp` and the scratch
directory. The system prompt is the workflow's `instructions`; the CLI reads no configuration of the workspace or of
a user.

**What it leaves in the record.** `step.started` (`mode: ai`), `ai.request` (provider, model, effort, tools, web,
prompt and system prompt), the CLI's `step.command` and `step.command.finished` (with `egress`: every host the
container asked for), one `ai.tool_call` per tool call, `ai.response` (the model that answered, tokens, cost, exit
code, the answer or the error), `step.output`, `step.finished` with `provider`, `model` and `aiRequest` in its
metadata. A `gate_status:` line in the answer sets a gate: a report step may deliver its verdict this way.

**Pause, resume, failure.** A pause lets the call run to its end. A cancel aborts it. After an interruption,
`resume` asks the model again, and the answer may differ. A call that fails (the CLI's exit code, a login prompt, a
missing result line) fails the step with the CLI's error; a provider the run does not have fails it before any call.

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "c2a7e9f1-3b5d-4c8a-9e0f-1a2b3c4d5e12",
  "name": "Simple block",
  "description": "Asks a model to explain the newest entry of the changelog.",
  "requires": ["ai.tools"],
  "blocks": [
    {"type": "simple", "name": "explain", "config": {
      "command": "Read CHANGELOG.md and say in three sentences what its newest entry changes for a user. Quote the line you rely on.",
      "provider": "CLAUDE",
      "model": "sonnet",
      "tools": "read",
      "untrusted_input": true,
      "timeout_seconds": 600}}
  ]
}
```

**Limits and refusals.** One call, one answer; nothing checks what it says, so let a later step check it against
the facts. Its answer is model text: later shell lines take it through `values`. A `simple` block in a `switch` case
is a block of its own and carries its own settings.

### agentic

Lets a model work towards a `goal` in a loop. Each iteration asks the model for a plan (REASON: a goal status, a
rationale and the next action), runs the action as a call of its own with the block's tools (ACT), and hands the
result back (OBSERVE). The step completes only when the model reports the goal achieved.

| Key | Default | Bounds | Meaning |
|---|---|---|---|
| `goal` | | required | what the loop works towards; placeholders are filled in as text |
| `success_criteria` | empty | | how the model tells that the goal is reached |
| `tools` | | required: `none`, `read`, `write`, `shell` | the tools of every call of the loop, its reasoning calls included; without the key the workflow is refused (`tools-required`) |
| `workspace_writable` | `false` | | the calls' containers may write the workspace |
| `max_iterations` | 10 | 1 to 10,000 | iterations at most |
| `iteration_timeout_seconds` | 300 | 1 to 86,400 | one iteration at most; checked after its action |
| `total_timeout_seconds` | 1,800 | 1 to 604,800 | the whole loop at most; checked before each iteration |
| `max_total_tokens` | 200,000 | 1 to 1,000,000,000 | the token budget: input and output tokens of every call, without the cached part |
| `max_repeated_actions` | 3 | 1 to 10,000 | the loop stops when the model proposes the same action this often |
| `require_approval` | `false` | | a person approves each action before it runs |
| `timeout_seconds` | 300 (the provider's) | 1 to 86,400 | one call at most; when set, the iteration timeout is raised to at least twice this plus 60 seconds, and the total timeout so that one iteration fits |
| `provider`, `model`, `model_choice`, `effort`, `web`, `web_hosts`, `untrusted_input`, `mcp_servers` | | | as for `simple` |

**What it may touch.** Every call runs as a `simple` call does, with the block's `tools` and `workspace_writable`:
with `shell` and a writable workspace, the model can change the project. The plan approval waits for a person.

**What it leaves in the record.** One `agentic.progress` per phase (`agentic_started`, `agentic_reasoning`,
`agentic_reasoned`, `agentic_acting`, `agentic_acted`, `agentic_goal_achieved` or `agentic_goal_impossible`, the stop
phases such as `agentic_token_budget_exceeded`, and `agentic_completed`), the `ai.request` to `ai.response` events of
every reasoning and acting call, `approval.requested` and `approval.answered` for each plan approval, `step.output`
(a line per iteration with its reasoning, action, exit code and result) and `step.finished` with the iterations,
the tokens and the stop reason in its metadata. The step sets `agentic_goal_achieved` and `agentic_iterations`.

**Pause, resume, failure.** A pause does not end the loop: at the start of its next iteration the step waits as long
as the run is paused, and since in a run of the command line nothing in its process lets it go on, it waits until the
run is cancelled or its process ends. A cancel stops the loop. After an interruption, `resume` starts the loop again
from its first iteration. The step fails when the model reports the goal impossible, when a limit stops the loop,
when three iterations in a row bring no token counts (for a provider that reports them), and when a reasoning call
fails. The plan approval offers `Yes`, `Yes always` (approve the rest without asking) and `No` (the model is told
and proposes another action); it waits in the process for at most 30 minutes, then the loop stops, and it never
parks the run (`check` warns `nested-wait-point`).

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "d3b8f0a2-4c6e-4d9b-8f1a-2b3c4d5e6f13",
  "name": "Agentic block",
  "description": "Lets a model make the unit tests pass, within a budget, in a checkout of its own.",
  "requires": ["ai.tools"],
  "blocks": [
    {"type": "agentic", "name": "fix-tests", "config": {
      "goal": "Make the unit tests of this project pass without changing the tests.",
      "success_criteria": "The test command exits with 0 and no file under src/test changed.",
      "provider": "CLAUDE",
      "tools": "shell",
      "workspace_writable": true,
      "max_iterations": 8,
      "max_total_tokens": 150000,
      "total_timeout_seconds": 3600}}
  ]
}
```

Run it with `traceworks run --source <repository>`, so that it works in a checkout of its own.

**Limits and refusals.** Use it only where the path to the goal is not known; where it is, write the steps. The
model decides when the goal is reached, so let a later step check it. An action longer than 100,000 characters is
condensed by the model before it runs.

## Control flow

`retry`, `conditional`, `while` and `foreach` hold a command, not blocks. They must write out `execution_mode`:
`shell` runs the command as a shell step, `ai` sends it to a model as a prompt. Without it the workflow is refused
(`explicit-execution-mode`), so that a step never asks a model the workflow did not name. A `foreach` that runs a
workflow per item needs none.

Their inner step takes the settings of the block:

- **In shell mode:** `container_image`, `mounts`, `network`, `workspace_writable`, `secrets`, `values`,
  `shell_escape_variables` and `timeout_seconds` (default 300), as a `shell` block reads them. An inner shell step
  has no `checked_command`, no tool mode and no `[RECOVERY]` line. A shell condition and an `items_command` run in the
  run's `shell` image without network, whatever `container_image` and `network` say, with the block's `values` and
  without its mounts or secrets.
- **In AI mode:** `provider`, `model`, `model_choice`, `effort`, `tools` (default `none`), `web`, `web_hosts`,
  `workspace_writable`, `untrusted_input` and `timeout_seconds` (default the provider's), as a `simple` block reads
  them. An AI condition always runs without tools. The output of a wrapper in AI mode sets no gate: a model wrote it.

`check` warns about a key of the other mode (`unknown-config-key`: `container_image` only takes effect with
`execution_mode: shell`).

### retry

Runs its `command` until an attempt completes, at most `max_retries` + 1 times, and waits between attempts.

| Key | Default | Bounds | Meaning |
|---|---|---|---|
| `execution_mode` | | required: `shell`, `ai` | how each attempt runs |
| `command` | | required | the command or the prompt; its placeholders are filled in once, so every attempt runs the same text |
| `max_retries` | 3 | 0 to 100 | attempts after the first |
| `delay` | 5 | 0 to 3,600 seconds | the wait before each further attempt |
| `exponential_backoff` | `false` | | doubles the wait after each retry: `delay`, twice `delay`, four times `delay`, ... |
| `timeout_seconds` | 300 (shell), the provider's (AI) | 1 to 86,400 | one attempt at most |

**What it leaves in the record.** The commands of every attempt (`step.command` and `step.command.finished`, or the
`ai.*` events of every call), then `step.output` with the last attempt's output and `step.finished` with `attempts`
and `maxAttempts` in its metadata. The output of an earlier shell attempt is not kept; its exit code is.

**Pause, resume, failure.** A pause lets the step run to its end, attempts and waits included. A cancel ends a wait at
once and starts no further attempt; the step ends `CANCELLED` unless an attempt completed. After an interruption,
`resume` runs the step again from its first attempt. An attempt that fails or times out is tried again; when none
completes, the step fails with the last attempt's output.

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "0b6f2a7e-6d1c-4c3e-8f0a-5b7e2d9c1a02",
  "name": "Retry block",
  "description": "A command that fails on its first attempt and completes on its second.",
  "blocks": [
    {"type": "retry", "name": "flaky", "config": {
      "execution_mode": "shell",
      "command": "set -euo pipefail; n=$(cat {{ scratch_dir }}/attempts 2>/dev/null || echo 0); n=$((n + 1)); echo $n > {{ scratch_dir }}/attempts; echo \"attempt $n\"; test $n -ge 2",
      "max_retries": 3,
      "delay": 1,
      "exponential_backoff": true,
      "timeout_seconds": 60}}
  ]
}
```

**Limits.** A retry repeats, it does not repair: an attempt runs the same command in the same workspace. It retries a
failure, not an output that fails `validation`; that is the `retry_on_fail` of `validation`.

### conditional

Evaluates `condition` and runs `then_command` when it holds, `else_command` when it does not.

| Key | Default | Bounds | Meaning |
|---|---|---|---|
| `execution_mode` | | required: `shell`, `ai` | how the condition and the branches run |
| `condition` | | required | shell: a command whose exit code 0 means true; AI: a statement the model calls true or false |
| `then_command`, `else_command` | none | | the branches; a missing one skips the step (`SKIPPED`, `No else branch defined, skipping`) |
| `on_condition_error` | `fail` | `fail`, `false`, `skip` | AI mode: what a failed evaluation means: the step fails, the condition counts as false, or the step is skipped |
| `timeout_seconds` | 300 (shell), the provider's (AI) | 1 to 86,400 | the condition at most, and the branch at most |

**How the condition is read.** A shell condition is true for exit code 0 and false for any other; one that timed out,
went beyond the output limit or did not run fails the step, since its exit code is no answer. An AI condition is sent
as `Evaluate this condition and respond with ONLY the word TRUE or FALSE, nothing else: <condition>`, without tools
and without the workflow's instructions; an answer that starts with `TRUE` (any case) is true, any other is false. A
call that fails is an evaluation error, which `on_condition_error` settles; a provider the run does not have always
fails the step.

**What it leaves in the record.** The condition's command or call, the branch's commands or calls, `step.output` with
the branch's output, and `step.finished` whose input reads `if (<condition>) → true` and whose metadata has
`conditionMet`.

**Pause, resume, failure.** The step runs to its end at a pause; after an interruption `resume` evaluates the
condition again. A failed branch fails the step.

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "3a9d4c18-2b7f-4e6a-9c0d-8e1f6a5b4c03",
  "name": "Conditional block",
  "description": "Says whether the workspace has a README.",
  "blocks": [
    {"type": "conditional", "name": "readme", "config": {
      "execution_mode": "shell",
      "condition": "test -f README.md",
      "then_command": "set -euo pipefail; head -n 1 README.md",
      "else_command": "echo 'no README.md'"}}
  ]
}
```

**Limits.** One condition and two branches; for more paths use a `switch`. A shell condition does not see the
block's image, mounts or network.

### while

Runs its `command` as long as `condition_command` holds, at most `max_iterations` times; without a condition, exactly
that often.

| Key | Default | Bounds | Meaning |
|---|---|---|---|
| `execution_mode` | | required: `shell`, `ai` | how the condition and each iteration run |
| `command` | | required | the body of the loop |
| `condition_command` | none | | checked before each iteration. Shell: exit 0 goes on, any other exit ends the loop. AI: the text is the prompt as it is; an answer `false` (any case), `0` or nothing ends the loop, any other goes on |
| `max_iterations` | 100 | 1 to 10,000 | iterations at most |
| `on_condition_error` | `fail` | `fail`, `false`, `skip` | AI mode: what a failed evaluation means: the step fails, the loop ends, or the step is skipped |
| `timeout_seconds` | 300 (shell), the provider's (AI) | 1 to 86,400 | one condition and one iteration at most |

Each iteration moves `{{ previous_output }}`, so the next one can take up what the last one produced.

**What it leaves in the record.** The commands or calls of every condition and iteration, `step.output` with a line
`[iteration N]: <output>` per iteration, and `step.finished` with `iterationCount` and `iterations` (index, status and
duration of each, the first 500) in its metadata.

**Pause, resume, failure.** A pause lets the loop run until its condition or its limit ends it; the run parks after
the step. After an interruption, `resume` runs the loop again from its start. A failed iteration ends the loop and
fails the step with the output so far; so does a shell condition that timed out or went beyond the output limit.

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "5e2b8f61-9a4d-4b7c-a1e3-0f6d2c8b7a04",
  "name": "While block",
  "description": "Runs a step until a counter in the scratch directory reaches three.",
  "blocks": [
    {"type": "while", "name": "count-up", "config": {
      "execution_mode": "shell",
      "condition_command": "test \"$(cat {{ scratch_dir }}/n 2>/dev/null || echo 0)\" -lt 3",
      "command": "set -euo pipefail; n=$(cat {{ scratch_dir }}/n 2>/dev/null || echo 0); echo $((n + 1)) > {{ scratch_dir }}/n; echo \"round $((n + 1))\"",
      "max_iterations": 10}}
  ]
}
```

**Limits.** The loop ends by its condition or its limit, never by itself otherwise: keep `max_iterations` small where
each iteration asks a model.

### foreach

Runs its `command` once per item, or with `workflow_id` a workflow per item. It is how one question is asked per
file, per finding or per dependency, instead of one large prompt.

| Key | Default | Bounds | Meaning |
|---|---|---|---|
| `execution_mode` | | required unless `workflow_id`: `shell`, `ai` | how each item runs |
| `command` | | `command` or `workflow_id` | the command or the prompt per item |
| `workflow_id` | | `command` or `workflow_id` | a workflow per item, as `sub-workflow` names one |
| `context` | none | only with `workflow_id` | an object from a variable name of the child to a template, filled in per item; it may not set a run variable, a platform variable or the item's own variables |
| `items` | none | a list, or text with one item per line | the static items; blank ones never count |
| `items_source` | `list` | `list`, `command` | `command` takes the items from `items_command` |
| `items_command` | none | | a shell command whose output lines are the items; it runs in the run's `shell` image without network, also in AI mode |
| `variable_name` | `item` | | the name the item gets: `{{ item }}`, `{{ item_index }}` (from 0) and, for an item that is a JSON object, `{{ item.<field> }}` |
| `continue_on_error` | `false` | | the step completes although items failed |
| `max_consecutive_failures` | 3 | 0 to 10,000 | after this many failed items in a row the loop stops and the step fails, also with `continue_on_error`; 0 switches it off |
| `timeout_seconds` | 300 (shell), the provider's (AI) | 1 to 86,400 | one item at most, and the `items_command` at most |

**What it may touch.** Each item runs as the inner step of its mode, or as a child run. Items from `items_command`
carry the run's taint: in a run that asked a model they are protected, and a shell line takes them through `values`;
static items are the workflow's own text.

**What it leaves in the record.** The `items_command`'s `step.command` (with `item` null), then per item
`foreach.item.started`, the item's commands or calls (with `item` its index), and `foreach.item.finished` with its
status, its output and, for a workflow, the child run (`sub_run`). `step.output` has a line `[<item>]: <output>` per
item, and `step.finished` has `totalItems`, `items` (the first 500), and `failedItemCount` and `failedItems` when
items failed, in its metadata.

**Pause, resume, failure.** A failed item does not stop the loop: the other items run, and the step fails after the
last one, naming the failed items, unless `continue_on_error` is set. A pause takes effect between two items of a
top-level `foreach`: the run parks (`run.parked` names the step), and `resume` runs the loop again and takes over the
items its record holds as finished (`foreach.item.finished` with `resumed_from`) instead of running them; after an
interruption it does the same. A `foreach` that runs a workflow per item does not park between its items, and its
resume runs every item again. An `items_command` that times out, goes beyond the output limit or does not run fails
the step before the first item.

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "8c1e5a39-7f2b-4d6e-b0a4-3c9e1d7f5b05",
  "name": "Foreach block",
  "description": "Counts the lines of each text file of the workspace, one item per file.",
  "blocks": [
    {"type": "foreach", "name": "lines", "config": {
      "execution_mode": "shell",
      "items_source": "command",
      "items_command": "set -euo pipefail; find . -maxdepth 1 -name '*.txt' | sort",
      "variable_name": "file",
      "command": "set -euo pipefail; wc -l < {{ file }}",
      "continue_on_error": true}}
  ]
}
```

A workflow per item, with the item handed to the child as `target`:

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "4c8f1a6e-2d9b-4e3a-a7f0-5b1c9e3d6a19",
  "name": "Foreach of workflows",
  "description": "Runs the file check once per file.",
  "blocks": [
    {"type": "foreach", "name": "each", "config": {
      "items": ["a.txt", "b.txt"],
      "workflow_id": "./child",
      "context": {"target": "{{ item }}"}}}
  ]
}
```

`./child` is the `File check` workflow of the `sub-workflow` example below.

**Limits and refusals.** `command` and `workflow_id` exclude each other. An `items_command` without
`items_source: command` never runs (`items-command-without-source`). The outputs a loop gathers count against the
run's output budget (128 MiB unless the operator sets another): the item that would go beyond it ends the loop and
fails the step and the run. An approval or a human input in a workflow per item never parks the run.

### switch

Fills in its `expression` and runs the blocks of the first case whose `value` matches it, else the blocks in `default`.

| Key | Default | Bounds | Meaning |
|---|---|---|---|
| `expression` | | required | a text with `{{ }}` placeholders; `${var}` is refused (`dollar-placeholder-in-switch`) |
| `cases` | | required | a list of `{"value": ..., "blocks": [...]}`; a value may list alternatives separated by `\|` (`"error\|critical"`) |
| `default` | none | | the blocks to run when no case matches |

The expression and each alternative are compared trimmed, so an answer that ends with a line break still matches. When
no case matches and there is no `default`, the step completes with the output `No matching case for: <value>`.

**The blocks of a case** are `shell` and `simple` blocks, each with its own `type`, `name` and `config`; a block of
any other type fails the switch when its case runs. A case block is run as the inner step of a wrapper: a `shell`
block with its command, image, mounts, network, writable workspace, secrets, values and timeout, without
`checked_command`, tool mode, `run_if` or `validation`; a `simple` block as a model call with its own settings, or, with
`execution_mode: shell` in its config, as a shell command. Each moves `{{ previous_output }}`, and a named one is
`{{ block.<name>.output }}` for the blocks after the switch. A failed case block fails the switch; the case goes on
with its next block only when the failed one says `on_failure: continue`, and the switch's own `on_failure` decides
for the run.

**What it leaves in the record.** The commands and calls of the case blocks (under the switch's step), `step.output`
with their outputs one after another, and `step.finished` with `expression`, `matchedCase` and `caseMatched` in its
metadata. The case blocks take step indices of their own.

**Pause, resume, failure.** The switch runs its case to the end at a pause; after an interruption, `resume` runs it
again from its first case block.

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "2f7a9c46-1e3b-4a8d-9f5c-6b0e4d2a8c06",
  "name": "Switch block",
  "description": "Takes one of three paths by the input level.",
  "default_context": {"level": "warn"},
  "blocks": [
    {"type": "switch", "name": "route", "config": {
      "expression": "{{ level }}",
      "cases": [
        {"value": "error|critical", "blocks": [
          {"type": "shell", "name": "stop", "config": {"command": "echo 'stop the release'"}}]},
        {"value": "warn", "blocks": [
          {"type": "shell", "name": "note", "config": {"command": "echo 'release with a note'"}},
          {"type": "shell", "name": "tell", "config": {"command": "echo 'tell the team'"}}]}
      ],
      "default": [
        {"type": "shell", "name": "go", "config": {"command": "echo 'release'"}}]}},
    {"type": "shell", "name": "after", "config": {
      "values": {"ROUTE": "{{ block.route.output }}"},
      "command": "set -euo pipefail; printf 'the route said: %s\\n' \"$ROUTE\""}}
  ]
}
```

**Limits.** `traceworks check` does not report a case block of another type than `shell` and `simple`, nor a
`run_if` on a case block, which no run reads; and it calls `execution_mode` on a `simple` case block a key without
effect, though the run then runs the block's command as a shell command.

## A person

`approval` and `human-input` ask a question and wait for the answer. Where nobody can answer in the process (no
terminal, or `--no-input`), a top-level question that a person answers alone **parks** the run: the question is
recorded, the run ends `PARKED` (exit 5), and `traceworks approve` or `traceworks answer` gives the answer, after
which `traceworks resume` goes on (docs/cli.md, "Interactive and parked runs" and "approve and answer"). A parked
question has no timeout, and `resume` refuses to go on before it is answered. A question in a child workflow, and one
an AI actor answers or proposes, never parks: it waits in the process. `check` warns about a question in a child
workflow (`nested-wait-point`).

**The actor.** Both types take `actor`: `human` (the default), `ai` (one model call in the person's role answers,
without tools) or `shadow` (the model proposes first, and the person decides with the proposal shown below the
question). `actor_prompt` gives the role its instructions; `provider`, `model`, `model_choice` and `effort` choose
the model, and `timeout_seconds` (1 to 86,400) bounds its call, though `check` calls that key unknown for these types.
`actor`, `mode` and `auto_answer` may be placeholders, so that a run's input casts the roles; in a run that carries
untrusted input such a placeholder is not filled in (the person is asked, nothing is answered automatically), and
`actor: ai` becomes `shadow`. The step's metadata says who answered (`answeredBy`), and of a shadow step the proposal
(`shadowAnswer`) and whether the person agreed (`agreed`).

### approval

A person answers `question` with one of `options`. A rejection follows `on_no`, any other answer approves.

| Key | Default | Bounds | Meaning |
|---|---|---|---|
| `question` | `Approval required` | | the question; placeholders are filled in, so the person sees the basis |
| `options` | `Yes`, `Yes always`, `No` | | the answers offered; an answer must be one of them (any case) and is kept in its spelling |
| `reject_options` | `No` and the German word for it | | the options that count as a rejection |
| `on_no` | `continue` | `continue`, `skip`, `fail` | after a rejection the step completes (`Approval denied (continuing): No`), is skipped, or fails (`Approval denied: No`) |
| `variable_name` | `approval_answer` | | the variable the answer goes into |
| `timeout_minutes` | 30 | 0 to 10,080 | how long a question in the process waits; after it the step fails (`Timed out waiting for approval`); 0 times out at once |
| `mode` | `ask` | `ask`, `auto` | `auto` with `auto_answer` answers without asking |
| `auto_answer` | none | | the answer in `auto` mode; it is not checked against `options`, and any answer that is no rejection approves |
| `actor`, `actor_prompt` | `human` | `human`, `ai`, `shadow` | see "The actor" |

A person's `Yes always` answers the same question again, later in the run and after a resume, without asking; an AI
actor's does not.

**What it leaves in the record.** `step.waiting`, `approval.requested` (the question, the options, the rejections,
whether it parks), `approval.answered` (the answer, who gave it, the agent when one named itself, and where it came
from: `terminal`, `inbox`, `cli` or `resume`), and for a parked question `run.parked`, then after the answer
`run.resumed`, the question again and `approval.answered` with the source `resume`. An answer in `auto` mode, or a
remembered `Yes always`, asks nothing and records no question. `step.output` is `Approved: Yes` or the rejection's
text, and an AI actor adds its `ai.*` events.

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "6d4b2e8a-3c1f-4e7b-8a9d-1f5c3e7b9d07",
  "name": "Approval block",
  "description": "Asks a person before the step that publishes.",
  "blocks": [
    {"type": "shell", "name": "build", "config": {"command": "set -euo pipefail; echo 'version 1.4.0 built'"}},
    {"type": "approval", "name": "publish-ok", "config": {
      "question": "Publish {{ block.build.output }}?",
      "options": ["Yes", "No"],
      "on_no": "fail",
      "timeout_minutes": 1440}},
    {"type": "shell", "name": "publish", "config": {"command": "set -euo pipefail; echo \"published, answer: {{ approval_answer }}\""}}
  ]
}
```

Run without a terminal, it parks at the question:

```
$ traceworks run --no-input --quiet approval-example
run 5c68a882-... PARKED
...
$ traceworks approve 5c68a882-... --answer No
The answer to step 1 of run 5c68a882-... is recorded; continue the run with traceworks resume 5c68a882-...
$ traceworks resume --quiet 5c68a882-...
run 5c68a882-... FAILED
  ...
  error   Step 1 (approval) failed: Approval denied: No
```

**Limits and refusals.** With `on_no: fail` or `skip` and no option that counts as a rejection, `on_no` could never
apply, and `check` reports `on-no-without-reject-option`. An answer that is no offered option is refused, also one
given while the run was parked. Ask at the top of a workflow, once, not inside a loop; a question whose text embeds
`run_timestamp`, `started_at` or `trigger_source` cannot be answered after a park (`question-changes-on-resume`).

### human-input

A person gives a value, or fills a form whose fields each go into a variable of their own.

| Key | Default | Bounds | Meaning |
|---|---|---|---|
| `prompt` | `Please provide input:` | | the question; placeholders are filled in |
| `input_type` | `text` | `text`, `textarea`, `number`, `select`, `checkbox`, `date` | the kind of value |
| `variable_name` | `user_input` | | the variable the answer goes into |
| `options` | none | | the choices of a `select`; a value of another type is not checked against them |
| `default_value` | none | | the answer after a timeout; placeholders are filled in |
| `required` | `true` | | an empty or blank answer is refused |
| `validation_pattern` | none | a regular expression | the whole answer must match it, as if it began with `^` and ended with `$`; a `select` and a `checkbox` are not matched against it |
| `timeout_minutes` | 30 | 0 to 10,080 | how long a question in the process waits |
| `use_default_on_timeout` | `true` | | after the timeout the default answers; without a default, or with `false`, the step fails (`Timed out waiting for human input`) |
| `fields` | none | a list of objects | a form: each field has `variable_name` and may have `label`, `input_type`, `required`, `default_value`, `options`, `placeholder`, `description` and `validation_pattern` |
| `actor`, `actor_prompt` | `human` | `human`, `ai`, `shadow` | see "The actor" |

**The answer.** A single input sets `variable_name` and is the step's output. A form's answer is a JSON object of
every field's value; each field's value goes into the field's `variable_name` (a missing one as empty text), and the
JSON object is the output. A person's answer is the person's: a later shell line takes it as it is. The default after
a timeout and an AI actor's answer are made of what the run holds, and are protected where the run's outputs are.

**Who checks the answer.** Every answer is checked against the rules of its input, whoever gives it and however it
comes: at the prompt of a run in the foreground, with `traceworks answer` (or the MCP tool `answer`) to a parked or
a running run, when a resumed step takes over the answer given while the run was parked, and from an AI actor. The
rules are `required`, `input_type` (a `number` as Java reads one, a `date` as `YYYY-MM-DD`, a `checkbox` as `true`,
`false`, `yes`, `no` or the German words, a `select` as one of its `options` in any case) and `validation_pattern`.
A form checks each field against its own rules, and a field that the answer leaves out is empty, so a required one
is refused. The value is checked as it is given, blanks included (an AI actor's answer is taken without the blanks
around it), and the step receives what was checked: a `select` in its option's spelling, a `checkbox` as `true` or
`false`. A pattern that is no regular expression, or whose check of a value does not end within 2 seconds, takes no
value, and one given up once is not tried again in that process (docs/security.md, "Patterns and reports are read
under a time limit").

A refused answer is recorded as no answer and the question still waits. The prompt says why and asks again;
`traceworks answer` exits 6 and names the field and the rule; a running run's process refuses the request
(`request.refused`, with the same reason), and the command that sent it prints the reason and exits 6:

```
$ traceworks answer 4361cb24-... --field version=1.4 --field date=2026-11-30
traceworks: the answer is refused:
  | The human input refuses the value for 'version' (validation_pattern): '1.4'
  |+ does not match the pattern [0-9]+\.[0-9]+\.[0-9]+
$ traceworks answer 4361cb24-... --field version=1.4.0 --field date=30.11.2026
traceworks: the answer is refused:
  | The human input refuses the value for 'date' (input_type): '30.11.2026' is
  |+ not a date in the form YYYY-MM-DD
```

A resumed step whose recorded answer the rules refuse (one a version before this check recorded, or a package that
`--allow-changed-package` let change) fails with `the answer given while the run was parked is refused: ...` and
sets no variable.

**What it leaves in the record.** As `approval`, with the kind `human-input`: `approval.requested` holds the input
type, the default, `required`, the pattern, the variable and the fields.

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "9a3e7c15-4b2d-4f8a-b6c1-2e8d4a6f0b08",
  "name": "Human input block",
  "description": "Asks a person for the release channel and uses it.",
  "blocks": [
    {"type": "human-input", "name": "channel", "config": {
      "prompt": "Which channel does this release go to?",
      "input_type": "select",
      "options": ["stable", "beta"],
      "variable_name": "release_channel",
      "default_value": "beta",
      "timeout_minutes": 60}},
    {"type": "shell", "name": "announce", "config": {"command": "set -euo pipefail; echo \"release goes to {{ release_channel }}\""}}
  ]
}
```

A form, answered with `traceworks answer <run> --field version=1.4.0 --field date=2026-11-30`:

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "c8a3e5f7-9b1d-4c4a-9e6f-7a8b9c0d1e18",
  "name": "Human input form",
  "description": "Asks for a version and a date in one form.",
  "blocks": [
    {"type": "human-input", "name": "release", "config": {
      "prompt": "Release details",
      "fields": [
        {"variable_name": "version", "label": "Version", "validation_pattern": "[0-9]+\\.[0-9]+\\.[0-9]+"},
        {"variable_name": "date", "label": "Date", "input_type": "date", "required": false}]}},
    {"type": "shell", "name": "show", "config": {"command": "set -euo pipefail; echo \"{{ version }} on {{ date }}\""}}
  ]
}
```

**Limits.** A person's decision per item that must hold in the next run belongs in a register, not in a human input
(docs/registers.md). The default after a timeout is the workflow's own value and is not checked against the rules;
it is protected where a placeholder in it is. The rules say what form a value has, not what it means: a text that
matches the pattern is taken, so let the pattern hold only what a later step may receive.

## Composing

### sub-workflow

Runs another workflow as one step and hands its output on.

| Key | Default | Bounds | Meaning |
|---|---|---|---|
| `workflow_id` | | required | a package-relative path (starting with `.` or holding a `/`, such as `./child`) or a workflow id; placeholders may name only trusted variables, never `{{ previous_output }}`, a block's output or an untrusted value |

The legacy key `workflowId` is not read: a block with it alone fails, and `check` reports `legacy-config-key`.

**The child run.** The child runs in the same process, with a run id of its own and the run's control: a cancel stops
both. It gets the parent's variables (without the platform data) and `{{ previous_output }}` as its inputs, over its
own `default_context`, and it shares the workspace and the scratch directory of the run that started first. Children
nest five deep at most, and a workflow that calls itself, directly or through others, is refused. A child workflow
in a folder of its own, below the run's package or elsewhere, is a package of its own: its lines in the record begin
with `run.package`, which names that package and its digest, and an image `./images/<name>` of it is refused
(`child-package-image`).

**What it leaves in the record.** The child's events stand in the parent's log, each with the parent's id in
`parent`: `run.package` first for a child of another package, then its steps, and its own `run.finished`; a child has
no `run.started` and no `run.checkpoint`. Then the parent's `step.output` (a copy of the child's last output, and a
line `Error: <the child's error>` when the child failed) and `step.finished` with `subRunId`, `subRunStatus`,
`subWorkflowId`, `subWorkflowRef` and `subWorkflowName` in its metadata.

**Pause, resume, failure.** A pause does not stop the child: it runs to its end, and the run parks after the step.
After an interruption, `resume` runs the child again from its start. A child that ends `FAILED` fails the step, and
one that ends `COMPLETED_WITH_ERRORS` completes it, with `subRunWarning` in the metadata; a cancel ends the child,
the step and the run `CANCELLED`.

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "4c8f1a6e-2d9b-4e3a-a7f0-5b1c9e3d6a09",
  "name": "Sub-workflow block",
  "description": "Runs the check of a subfolder as one step.",
  "default_context": {"target": "a.txt"},
  "blocks": [
    {"type": "sub-workflow", "name": "checks", "config": {"workflow_id": "./child"}},
    {"type": "shell", "name": "after", "config": {
      "values": {"CHILD": "{{ block.checks.output }}"},
      "command": "set -euo pipefail; printf 'child said: %s\\n' \"$CHILD\""}}
  ]
}
```

`child/workflow.json` in the package:

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "1e5a9c3f-7b2d-4a6e-9c8b-0d4f2a6e8c10",
  "name": "File check",
  "description": "Checks that one file of the workspace exists.",
  "default_context": {"target": "README.md"},
  "blocks": [
    {"type": "shell", "name": "exists", "config": {"command": "set -euo pipefail; test -f {{ target }} && echo \"{{ target }} is there\""}}
  ]
}
```

**Limits.** The child's gate and its errors stay in the child: its output is a copy and sets no gate in the parent,
and a child that completed with errors leaves the step `COMPLETED`. A parent that needs the verdict carries it up
with a step of its own (docs/cli.md, "When a step fails"). The child has no time limit of its own; its steps' timeouts
bound it.

## Condensing

`compress` and `compress-ai` take the output of one earlier step and make it shorter for the steps after them. The
original output stays as it is. The output of either is a header line with the facts of the source step, a detail
line, an empty line and the content, at most `max_chars` long: content that does not fit is cut in its middle with a
note. An empty result shows `(empty)`. Their output is derived: it sets no gate.

`source` is the name of an earlier block, or `previous` for the step before; it is no placeholder, and a name that
two blocks share is refused. A source that is itself a compression is taken without its header lines.

### compress

Shortens the source by a chain of fixed stages, in the Traceworks process: no container, no command, no model.

| Key | Default | Bounds | Meaning |
|---|---|---|---|
| `source` | | required | the block whose output is shortened, or `previous` |
| `stages` | | required, not empty | the stages, in order, each an object with `op` and its settings |
| `max_chars` | 20,000 | 300 to 1,000,000 | the length of the whole output at most |
| `timeout_seconds` | 30 | 1 to 600 | the whole chain at most; a pattern that takes longer fails the step |

The stages:

| `op` | Settings | What it does |
|---|---|---|
| `text.normalize` | none | removes ANSI sequences, carriage returns and trailing whitespace, and collapses runs of empty lines |
| `text.truncate` | `max_chars` (100 to 1,000,000, required), `keep` (`head`, `tail` or `head_tail`, the default) | cuts the text, at line boundaries where it can, with a note of what it left out |
| `lines.filter` | `include`, `exclude` (lists of patterns; at least one), `before`, `after` (0 to 1,000 lines of context) | keeps the lines that match `include`, marking the gaps, and drops those that match `exclude` |
| `lines.replace` | `pattern` (required), `replacement` (default empty, `$1` for a group) | replaces in every line |
| `lines.dedupe` | `count`, `consecutive_only` (booleans) | drops repeated lines, with `consecutive_only` only those that repeat the line before; with `count` the first one reads `37× line` |
| `lines.head`, `lines.tail` | `lines` (1 to 1,000,000, required) | keeps the first or the last lines, with a note of how many it left out |
| `markdown.section` | `headings` (patterns, at least one), `level` (1 to 6) | keeps the sections whose heading matches (any case), up to the next heading of the same or a higher level; a heading in a code fence does not count, and no match is a note, not an error |
| `json.select` | `paths` (at least one, such as `items[].title`) | keeps only these paths; the result stays JSON |
| `json.limit` | `path` (default the root), `count` (0 to 1,000,000, required) | shortens an array |
| `json.sort` | `path`, `by` (required), `order` (`asc`, the default, or `desc`) | sorts an array by a field |
| `json.to_lines` | `path`, `template` (required, fields in single braces: `{title} at {url}`) | one line per array entry |

The whole chain is checked before any stage runs: an unknown `op`, a missing or wrong setting, and a `json.*` stage
after a stage that yields text fail the step, and `check` reports each as `invalid-config-value`; a setting the stage
does not read is the warning `unknown-config-key`. No placeholder in the block's config is filled in.

**What it leaves in the record.** `step.started`, `step.output` and `step.finished` with `compression` in its
metadata: the source step's facts, the characters in and out, `capped`, and each stage with its characters in and out.
There is no `step.command`: nothing runs outside the process.

**Pause, resume, failure.** It ends within its time limit; after an interruption `resume` runs it again. A failure
(`Compression failed: <reason>`) follows `on_failure`.

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "7b2e4d9a-5c1f-4a3b-8e6d-9f0a2c4e6b11",
  "name": "Compress block",
  "description": "Keeps the error lines of a long log for the next step.",
  "blocks": [
    {"type": "shell", "name": "build-log", "config": {"command": "set -euo pipefail; for i in $(seq 1 50); do echo \"INFO step $i\"; done; echo 'ERROR test FooTest failed'; echo 'ERROR test BarTest failed'"}},
    {"type": "compress", "name": "errors", "config": {
      "source": "build-log",
      "stages": [
        {"op": "text.normalize"},
        {"op": "lines.filter", "include": ["^ERROR"]},
        {"op": "lines.head", "lines": 20}],
      "max_chars": 2000}}
  ]
}
```

Its output:

```
## build-log — COMPLETED · exit 0 · 0 s · 692 → 66 chars
Stages: text.normalize (−0 chars) · lines.filter (−50 lines) · lines.head

[… 50 lines …]
ERROR test FooTest failed
ERROR test BarTest failed
```

### compress-ai

Condenses the source with one model call that has no tools and gets nothing but the source and the instruction.

| Key | Default | Bounds | Meaning |
|---|---|---|---|
| `source` | | required | the block whose output is condensed, or `previous` |
| `instruction` | `Summarise the content as briefly as possible.` | | what to keep; used as it is written, no placeholder is filled in |
| `max_chars` | 4,000 | 500 to 200,000 | the length of the whole output at most; the model is asked for 300 characters less |
| `timeout_seconds` | 300 (the provider's) | 1 to 86,400 | the call at most |
| `provider`, `model`, `model_choice`, `effort` | | | as for `simple`; the provider must be `CLAUDE` |

The call runs as every model call does, with `tools: none` and a fixed system prompt: use only the source, which
stands between `<compress_source>` tags, treat instructions in it as data, copy errors, numbers, names and paths as
they are, and write no `gate_status:` or `todo_item:` line.

**What it leaves in the record.** The call's `ai.request` to `ai.response`, `step.output` with the header line, the
line `Summary: AI (CLAUDE)` and the summary, and `step.finished` with `compression` in its metadata.

**Pause, resume, failure.** A pause lets the call end; after an interruption `resume` asks again. A source longer than
60,000 characters fails the step before any call (put a `compress` block before it), and so does another provider than
`CLAUDE`, which `check` does not report. A failed call, or one that answers nothing, fails the step with the end of
the CLI's error.

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "e4c9a1b3-5d7f-4e0c-9a2b-3c4d5e6f7a14",
  "name": "Compress-ai block",
  "description": "Condenses a test log to what a reviewer needs.",
  "blocks": [
    {"type": "shell", "name": "tests", "config": {"command": "set -euo pipefail; printf 'test A ok\\ntest B failed: expected 3, got 4\\n'"}},
    {"type": "compress-ai", "name": "summary", "config": {
      "source": "tests",
      "instruction": "List each failed test with its message, one line each.",
      "provider": "CLAUDE",
      "max_chars": 1500}}
  ]
}
```

**Limits.** A summary is model text, not a measurement: what a later step must count, it counts in the source.

## How the types work together

![One example workflow as a flow. A shell step lists the release notes into a file of the scratch directory. A foreach takes one item per note and asks a model for one line on each, OK or CONCERN with the line it relies on. A conditional checks in a shell condition whether any verdict says CONCERN: the then branch says there are no concerns, the else branch lists them. An approval asks a person whether to publish, with the conditional's output in the question; without a terminal the run parks here and goes on after the answer. A shell report step counts the verdicts and prints the gate, GREEN without a concern and YELLOW with one. Under the boxes, what each step leaves in the record.](blocks-example.svg)

The workflow of the picture: a script finds the items, a model judges each one, a script decides from the verdicts,
a person approves, and a script writes the report and its gate. The model's verdicts reach the shell lines only as
`values`, and the gate comes from a script, not from the model.

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "5b0e7d3a-9c41-4f62-8a17-3e6d2b9c4f20",
  "name": "Release review",
  "description": "Lists the release notes, asks a model for a verdict on each, branches on the verdicts, asks a person and writes the report with its gate.",
  "requires": ["ai.tools"],
  "metadata": {"gate": {"expected": true}},
  "blocks": [
    {"type": "shell", "name": "list", "config": {
      "command": "set -euo pipefail; find notes -name '*.md' | sort > {{ scratch_dir }}/notes.txt; wc -l < {{ scratch_dir }}/notes.txt"}},
    {"type": "foreach", "name": "judge", "config": {
      "execution_mode": "ai",
      "provider": "CLAUDE",
      "tools": "read",
      "untrusted_input": true,
      "continue_on_error": true,
      "items_source": "command",
      "items_command": "set -euo pipefail; cat {{ scratch_dir }}/notes.txt",
      "variable_name": "note",
      "command": "Read the release note {{ note }}. Answer with one line: OK when it is right for this release, else CONCERN and the line you rely on."}},
    {"type": "conditional", "name": "branch", "config": {
      "execution_mode": "shell",
      "values": {"VERDICTS": "{{ block.judge.output }}"},
      "condition": "! printf '%s\\n' \"$VERDICTS\" | grep -q CONCERN",
      "then_command": "echo 'No concerns.'",
      "else_command": "set -euo pipefail; printf '%s\\n' \"$VERDICTS\" | grep CONCERN"}},
    {"type": "approval", "name": "publish-ok", "config": {
      "question": "Publish the release? The review says: {{ block.branch.output }}",
      "options": ["Yes", "No"],
      "on_no": "fail"}},
    {"type": "shell", "name": "report", "config": {
      "values": {"VERDICTS": "{{ block.judge.output }}"},
      "command": "set -euo pipefail; total=$(printf '%s\\n' \"$VERDICTS\" | grep -c '^\\[' || true); concerns=$(printf '%s\\n' \"$VERDICTS\" | grep -c CONCERN || true); echo \"$total notes reviewed, $concerns with a concern\"; if [ \"$concerns\" -eq 0 ]; then echo 'gate_status: GREEN'; else echo 'gate_status: YELLOW'; fi; echo 'gate_category: release-review'; echo \"gate_description: $concerns of $total notes with a concern\""}}
  ]
}
```

- `list` writes the items to `{{ scratch_dir }}/notes.txt` and prints how many there are.
- `judge` asks one question per note, with `tools: read` so that the model can open the file, and
  `continue_on_error`, so that one failed call is one note without a verdict. Its output is a line `[<note>]:
  <verdict>` per note, and it sets no gate.
- `branch` reads the verdicts as `$VERDICTS`: its shell condition is true when none says `CONCERN`.
- `publish-ok` shows the branch's output in its question. Without a terminal, the run parks here; `traceworks approve
  <run> --answer Yes` and `traceworks resume <run>` go on.
- `report` counts the verdicts and prints the gate. With `metadata.gate.expected`, a run that ends without a gate
  would get the YELLOW gate `gate-missing`.

A workflow that judges items run after run, and keeps a person's decision per item, is an audit that feeds a register
(docs/registers.md); the self-audits in `examples/self-audit` are built in this shape.
