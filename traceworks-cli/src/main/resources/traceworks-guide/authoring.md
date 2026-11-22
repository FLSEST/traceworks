# Writing a Traceworks workflow

This is the short guide for whoever turns a task into a workflow, a person or a model. The rules of the format,
each with its reason, are the topic `format` of this tool, and findings across runs are the topic `registers`.
Both are long: ask with `search` for the words of your question (`topic: format`, `search: approval timeout`)
instead of reading them whole. Packages to read are in the tool `examples`.

## What a workflow is

A package is a folder with a `workflow.json` and what its steps need beside it: scripts, rule files, and
`images/<name>/Dockerfile` for a tool of its own. A run executes the blocks of the workflow in order and records
every step in a hash-chained log. Every step that executes something runs in a container: without network, with
the workspace read-only and with resource limits, unless the workflow asks for more and the operator allows it.

A workflow runs as it is written, or it is refused. Nothing is guessed, so write everything out.

## How to cut a task into steps

A good workflow is rarely all scripts or all model. Scripts are exact, cheap and the same every time, and they
cannot read. A model reads and judges, and it is neither exact nor the same every time. The value is in the
combination: each does what the other cannot, and each checks the other. Go through the task with these questions,
in this order.

1. **What can be measured, counted, compiled, compared or looked up?** That is a deterministic step: a `shell`
   block that runs a script or a tool. The same input gives the same output, anybody can run it again, and it costs
   no model call. Collect the facts first, as files or as a step's output: they are what a model is asked about
   and what its answer is checked against.
2. **What is a tool of its own?** A linter, a compiler, a scanner, a test run. Give it an image of the package
   (`images/<name>/Dockerfile`, named in the block as `"container_image": "./images/<name>"`, and
   `package.images` in `requires`). Pin the base image by digest. The tool then is the same in every run.
   A step has no network unless it says `"network": true` (and `container.network` in `requires`). Give it to
   the one step that must fetch (the build tool resolving dependencies), and let that step fetch everything the
   later steps need. A step with network asks the places the operator or the project named, and you
   never ask an address that the data names: a URL in a POM, a manifest or an issue is written by someone
   outside the project. What you want from it is often in what the build tool fetched already (the license file
   inside the JAR).
3. **Where does a model add something no script can?** Look for it in every task; most have such a place.
   - *Reading text written for people:* a license text, a changelog, an issue, a comment, a commit message. Which
     license is this, does this release note announce a breaking change, is this bug report a duplicate?
   - *Judging a candidate a script found:* the script finds every place that could be a problem, cheaply and
     completely; the model says for each whether it is one, and why. A finder alone reports too much, a model
     alone overlooks places.
   - *Explaining and proposing:* what a finding means for this project, a fix, a wording, what to look at first.
   - *Working where the path is not known:* an `agentic` block, for a goal that needs trying and looking.

   Do not leave a model out to keep a workflow "clean": a check that hands a person forty raw hits is worth less
   than one that hands over forty hits, each with a verdict, a reason and the line it rests on. And do not ask a
   model what a script answers: counting, comparing versions, parsing a file, looking something up in a list.
   A model call gets the facts of the steps before in its prompt and as few rights as it needs: `tools` is `none`
   unless the model must read files (`read`). Ask for an answer in a fixed shape.
4. **Where does a person decide?** There are two ways, and they answer different questions.
   - *One yes or no for the run:* an `approval` block before anything that leaves the run, such as a publish, a
     deploy or a change of files that others use. The record then names who said yes.
   - *One decision per item, which still holds in the next run:* a license, a finding, a file to look at. That is
     a register, not an approval and not a file of decisions of your own (below, "Findings a person decides").
5. **What does a person or the next step read at the end?** One last step writes the report, and where the
   workflow is a check, its gate (below).

## The pattern that combines both

Most checks and many other workflows come out as these four parts, and the self-audit examples are built this way
(`self-audit/architecture` is the shortest to read: three blocks).

1. **Find**, deterministic: a script or a tool writes the facts and one line per item to judge (a file, a
   dependency, an area of code) into `{{ scratch_dir }}`, for example as `candidates.jsonl`.
2. **Judge**, a model, once per item: a `foreach` with `execution_mode: ai`, `items_source: command` and an
   `items_command` that prints the items. Each call sees one item and its facts, and answers in a fixed shape
   with what it quotes as evidence. One question per item, never one large prompt: an answer then fails or is
   wrong for one item only, and the next run asks only about items that changed. Set `untrusted_input: true`
   when the item holds text from outside, and `continue_on_error: true` so that one failed call is one item
   without a verdict.
3. **Check**, deterministic: the report step reads the verdicts, drops or marks every one whose quoted evidence
   is not in the facts, and computes the gate. A model's answer is input to a script here, never the result.
4. **Decide**, a person: through a register, finding by finding (below), or with an approval for the run.

Keep it in proportion. The self-audit examples carry a tool of some thousand lines each, because they measure a
whole code base; take their cut, not their size. A first version is a `workflow.json`, a rule file and a few short
scripts that use tools which exist already (`jq`, the build tool of the project, a scanner), and it is written in
minutes. Write a tool of your own only when the measuring is the hard part, and say so before you do. Have
`check` judge the package as soon as the `workflow.json` stands, not after everything else is written.

The model then makes the result better and cannot make it wrong unnoticed: what it claims must rest on a line a
script found, and a person has the last word.

Signs of a workflow that should be cut differently: a list of raw hits for a person where a model could have
judged each, an AI step that could be a script, an AI step that both finds the facts and judges them, a model with `write` or `shell` tools where a script would do the writing, one large
prompt in place of one question per item (`foreach`), a list of a person's decisions kept in a file of the
package, and an approval that asks a person to edit something while the run waits.

## The file

```json
{
  "format": "traceworks-workflow",
  "format_version": 1,
  "id": "<a UUID of this workflow>",
  "name": "Licenses",
  "description": "What the workflow does, in one sentence.",
  "default_context": {"limit": "10"},
  "requires": [],
  "metadata": {"gate": {"expected": true}},
  "blocks": [
    {"type": "shell", "name": "measure", "config": {"command": "set -euo pipefail; bash \"{{ package_dir }}/scripts/measure.sh\" \"{{ limit }}\""}},
    {"type": "approval", "name": "publish", "config": {"question": "Publish?", "options": ["Yes", "No"]}}
  ]
}
```

- A shell command starts with `set -euo pipefail`, so that an error halfway through fails the step.
- A block has a `type`, a `name` that is unique in the workflow, and its settings in `config`.
- `default_context` holds the inputs of the workflow with their defaults; a run sets them with an input of the
  same name.
- `requires` names what the workflow needs beyond the defaults, so that nobody runs it by accident where that is
  not meant: `ai.tools` for an AI step whose `tools` is not `none`, `ai.web` for one with `web`,
  `container.network` for a shell step with `network: true`, `package.images` for an image of the package. The
  tool `check` reports a missing one.

## Placeholders

`{{ name }}` is the value of an input or of a variable a step set. The ones a workflow uses most:

- `{{ block.<name>.output }}`: the output of the earlier block of that name.
- `{{ previous_output }}`: the output of the step before.
- `{{ package_dir }}`: the folder of the package, for its scripts and rule files.
- `{{ scratch_dir }}`: a directory of the run that every step can write and the next step reads. Pass data
  between steps as files here once it is more than a few lines.

In a shell command a value that comes from outside the workflow is quoted for you. Do not build a command by
pasting text a model wrote into it.

## The block types

Deterministic, no model:

- `shell`: one command (`command`) in a container. `container_image` picks the image, `workspace_writable: true`
  lets it write the workspace, `secrets` names the secrets it gets as environment variables, `network: true`
  gives it a network. With `container_args` in place of `command` the image's own entrypoint runs as a tool.
  `checked_command: true` turns the exit code into a gate.
- `approval`: a person answers a `question` with one of its `options`; `on_no` says whether a No goes on, skips
  or fails. The run parks at the question and goes on after the answer.
- `human-input`: a person gives a value (`variable_name`, `input_type`) or fills a form (`fields`).
- `sub-workflow`: runs another workflow of the package (`workflow_id`) as one step.
- `compress`: shortens the output of an earlier block by fixed rules, without a model.

With a model:

- `simple`: one call with `command` as the prompt. `provider` is `CLAUDE` or `PI`, `model` names the model,
  `tools` is `none` (the default), `read`, `write` or `shell`. Set `untrusted_input: true` when the prompt holds
  text from outside (an issue, a web page, a file nobody of the team wrote).
- `agentic`: a loop of plan, act and observe towards a `goal`, bounded by `max_iterations`. It must state
  `tools`. Use it only where the path to the goal is not known beforehand; where it is, write the steps.
- `compress-ai`: condenses the output of an earlier block with one call that has no tools.

Control flow, each with `execution_mode` written out as `shell` or `ai`, so that the file says whether the step
asks a model:

- `foreach`: runs its `command` once per item (`items`, or the lines that `items_command` prints), with the item
  as `{{ item }}`. With `workflow_id` it runs a workflow per item. This is how one question per file, per
  finding or per dependency is asked.
- `conditional`: runs `then_command` or `else_command` by its `condition`.
- `while`: runs its `command` while `condition_command` holds, at most `max_iterations` times.
- `retry`: runs its `command` until it completes, at most `max_retries` + 1 times.
- `switch`: runs the blocks of the case that its `expression` matches.

## The gate

A workflow that checks something says how it ended. A step prints, as lines of its output:

```
gate_status: GREEN
gate_category: licenses
gate_description: 42 dependencies, none with a license outside the list
```

`gate_status` is `GREEN`, `YELLOW` or `RED`. With `"metadata": {"gate": {"expected": true}}` a run that ends
without a gate gets a YELLOW one that says so. Let a deterministic step compute the gate from the facts, not a
model.

## Findings a person decides

When a workflow finds things that a person judges one by one (a license that is unclear, a file that is too
large, a rule that is broken), make it an audit that feeds a register. The register knows a finding again in the
next run, keeps what a person decided about it and who decided, and reopens it when it gets worse. You write none
of that yourself. The example `audit-hello` is the smallest one and comes whole, with its scripts.

1. Declare the audit in `workflow.json`:
   `"metadata": {"audit": {"enabled": true, "key": "licenses", "category": "release", "report_block": "report"}}`.
   `key` names the register, and `report_block` is the block whose output is the report. A register is kept per
   `key` and per project: one audit package run on two projects feeds two registers, and a decision made for one
   does not cover the other. The package names no project; the run does (below, "Before the first run").
2. Put a `catalog.json` beside it that names each rule and the severities it may state:
   `{"format": 1, "rules": {"unclear-license": {"severity": {"min": "LOW", "max": "HIGH"}}}}`.
3. Let the report block end its output with the envelope, one line, written by a script from the facts:
   `<!-- traceworks:audit-result:v1 {"snapshotComplete": true, "expectedFindings": 1, "findings": [{"ruleKey":
   "unclear-license", "subject": "org.example:lib", "severity": "MEDIUM", "title": "...", "description": "...",
   "evidence": "..."}]} -->`.
   `ruleKey` and `subject` together are the identity of a finding, and a decision belongs to that identity. Choose
   a subject that stays the same from run to run (a coordinate without its version, a path), or every run reports
   new findings. And choose one that holds what the decision is about: a person who accepts `org.example:lib`
   under one license has not accepted it under the next one. So the subject of a license finding names the
   license (`org.example:lib [EPL-2.0]`), and that of an unclear text a digest of the text; when either changes,
   the finding is a new one and a person looks again.
4. A person then decides outside the run, finding by finding: `traceworks findings licenses list`, `show <id>`,
   and `accept <id> --note ...` or `dismiss <id> --note ...`, with `--project KEY` where the key has registers of
   several projects. Through this server those are the tools `findings_list`, `findings_show` and
   `findings_decide` with the argument `register`: they reach the registers of the project the operator bound to
   the folder this server works in, which `findings_registers` lists, and no other. `findings_verify` checks one of
   them. A register an earlier version kept without a project is refused until it is assigned: `findings_assign`
   gives it the project of this server, once and for good, so call it only when the person said so.
5. The next run knows the decisions: `{{ audit_findings }}` is a summary for a prompt, and
   `{{ register_dir }}/findings.jsonl` holds every finding with its status for a script. Let the gate follow them:
   RED or YELLOW while a finding is open, GREEN when each is decided or gone.

So a run does not wait for the person. It ends with a gate that says what is open, the person decides, and the
next run is green. That is also how a release check works: run it, decide what is open, run it again.

## People in a run

- A person sees of a run what its record holds: the output of each step (`traceworks show --outputs`, `run.html`)
  and the question of an `approval`. A person does not see the paths of a container: `{{ scratch_dir }}` is
  `/scratch` in there. What a person must read belongs in the output of a step, or in the question itself.
- An `approval` at the top of the workflow parks the run: it ends, and `resume` goes on after the answer, also
  days later. A question inside a loop or a child workflow does not park; it waits while the run's process lives.
  So ask at the top, once, and not once per item.
- Do not change the package while a run of it waits. A resume compares the package with the one the run started
  with and refuses when it changed. What a person decides goes into a register or into the answer, never into a
  file of the package.

## Before the first run

1. Write the package into the project, in the folder the operator keeps packages in.
2. Call the tool `check` on it and repair every error it reports. It knows the commonest mistakes: an unknown
   key, a placeholder nothing sets, a missing entry in `requires`, a control block without `execution_mode`.
3. Call the tool `describe` and show its result to the person. It says what each block will touch: the image,
   the network, what is writable, which model with which tools.
4. Start no run before the person has seen that and said so. A run may cost model calls and may write.
5. The run of an audit needs the project it works on, and the project is the operator's word: the operator binds
   the folder to a key (`"projects": {"shop": "/srv/shop"}` in the `traceworks.json` of the home), and a run of
   this server in that folder is of that project. You cannot name a project: leave the argument `project` of the
   tools out. Where the folder is bound to none, the run of an audit is refused and writes nothing; show the
   refusal to the person, it names the line to add. Do not look for a way around it.

What a workflow can never do is the operator's to open, in the `traceworks.json` of the home: a host run, a
writable mount, the secrets a run may take, the models that may run. Do not work around a refusal; show it to the
person.
