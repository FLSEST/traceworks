# Integration tests

A black-box suite: it tests Traceworks from the outside, as a user or a CI runner does. Each test starts the real
`traceworks.jar` (`traceworks-cli/target/traceworks.jar`) with `java -jar`, against a real container runtime, with a
small workflow package from `packages/`. No test calls a model: the AI blocks run against fake CLIs in fake images (see
"AI blocks"), and nothing needs the network except building the images.

The unit and component tests of the other modules stay where they are. This suite is a separate layer, and it runs
only when you ask for it: a plain `mvn verify` starts no container at all (docs/architecture.md, "Building and
testing").

## Run it

From the root of the repository, with Java 21, Maven, git and `docker` or `podman`:

```bash
mvn verify                                     # the default build: no container, the suite is skipped
java -jar traceworks-cli/target/traceworks.jar images build   # once, and after a change below images/
mvn -Pit verify                                # everything: the container tests of the other modules, then the suite
TRACEWORKS_IT_RUNTIME=podman mvn -Pit verify   # the suite with podman
scripts/container-footprint.sh mvn -Pit verify # the same, and what it made the runtime create
```

`-Pit` is the whole build with containers: it also runs the container tests of `traceworks-adapters` and
`traceworks-cli` (the tag `container`), which come before this module and need the built-in images `shell`, `claude`
and `pi` to be built. Those tests fail, with the command to run, when an image is missing; they never skip. Run one
container-starting build at a time on a machine: two of them at once multiply the network devices the host has to
handle.

- **The runtime** is `TRACEWORKS_IT_RUNTIME`: `docker` (the default) or `podman`. Each test writes it as
  `container_runtime` into its home's `traceworks.json`, so the suite never depends on which runtime Traceworks would
  detect first.
- **The images:** the suite builds what it needs once, at its start, with `traceworks images build <package>`: the
  built-in `shell` image and each package's own `images/<name>/Dockerfile`. The built-in AI images (`claude`, `pi`)
  are never built. The built-in `egress` image, the proxy of every AI call, is built on the first AI test, from
  `images/egress` with the runtime's own `build` (see "AI blocks"). The first build of `shell` installs Debian
  packages and needs the network; the fake AI images (below) and the `egress` image need the network once, for their
  base images.
- **Nothing is skipped.** A missing runtime, a runtime that does not answer `version`, a missing jar or an image that
  cannot be built fails the suite with a message that says what to do. CI can never pass by skipping.
- **Time:** about 14 minutes on a laptop once the images are built (the whole `mvn -Pit verify`, before the
  combination tests were added). An AI call behind the proxy starts the proxy first, and a
  run's first such call makes two networks, which costs about a second.
- **cgroup v2:** the limit tests read the container's own `/sys/fs/cgroup` files, as every current Docker and Podman
  on Linux provide them.

To run one class: `mvn -Pit verify -pl integration-tests -Dtest=SecretsIT` (after a full build, so that
`traceworks-cli/target/traceworks.jar` exists).

## What a run costs the host

On Linux every network a runtime creates is a bridge device, and every connection of a container to a network is a
veth pair with one end on the host. The machine's network services react to each of them, so a suite that makes
thousands is a load of its own. What the suite does about it:

- **A step's container has no network** (`--network none`): no device. That is every `shell` step of the suite but
  the few with `network: true`, which join the runtime's default bridge (one veth pair, no new bridge).
- **An AI call behind the egress proxy** is the expensive case: the run makes two networks (two bridges, once per
  run, however many calls it has; `AiBranchesIT` checks that), and every call starts a proxy container on both
  networks next to the CLI's container (three veth pairs per call).
- **Only the classes that are about the network pay that.** `AiEgressIT`, `AiWebIT`, `AiIsolationIT` and
  `AiBranchesIT` test where a container can connect, what the proxy records and what a run makes on the host;
  `AiSimpleIT` is the plain default path of both CLIs; `AiFailuresIT` and `AiPauseIT` stop calls and check what is
  left; `AiChildrenIT` checks whose proxy and networks the calls of child runs get. They run behind the real proxy.
  `AiToolsIT`, `AiAgenticIT`, `AiCompressIT`, `AiGatesIT`, `AiCredentialsIT`, `AiImagesIT`, `AiModelChoiceIT` and
  `AiModelTextIT` test the arguments a CLI gets, the record of a call, a gate, credentials, images, the model of a
  call and what becomes of its answer: they override
  `AiIntegrationTest.behindTheEgressProxy()`, their sandboxes get `ai_network: "open"`, and their runs make no
  network and start no proxy (one veth pair per call, on the default bridge). After each of their tests the frame
  checks that no command of a run went through a proxy. The price: the proxy is no longer exercised together with
  each of those features, only by the classes named first.
- **Nothing is shared between runs.** The two networks carry the run's id and are made and removed by Traceworks
  itself, so a black-box test cannot hand one pair to several runs; the suite can only choose which runs need them.

Measured on 2026-11-14 with Docker 29, `mvn -o -Pit verify`, one run each:

| | networks | network connections | devices on the host | containers | time |
|---|---|---|---|---|---|
| all AI classes behind the proxy | 64 | 289 | 353 | 864 | 870 s |
| six classes without it | 40 | 217 | 257 | 828 | 803 s |

Nothing was left behind in either run.

To count what a run of the suite, or any other command, made the runtime create:

```bash
scripts/container-footprint.sh mvn -o -Pit verify
scripts/container-footprint.sh mvn -o -Pit verify -pl integration-tests -Dtest='Ai*IT'
```

It follows `docker events` while the command runs and prints the containers created, the networks created (bridges),
the network connections (veth pairs), their sum as devices on the host, and the containers and networks left behind
(from `ps -a` and `network ls` before and after). The events are the daemon's, so anything else that starts
containers meanwhile is counted too: run it alone. `TRACEWORKS_FOOTPRINT_EVENTS=<file>` keeps the event lines.
With Podman only the before and after listing is printed.

## What it covers

| Class | Package | What it shows |
|---|---|---|
| `PlaceholdersIT` | `placeholders` | `shell` steps; `--input`, `{{ step_1_output }}` and `{{ block.<name>.output }}`; `shell_escape_variables`; `run_if` |
| `BranchesIT` | `branches` | `conditional` in shell mode and `switch` (alternatives with `\|`, the default case) |
| `LoopsIT` | `loops` | `foreach` with `items` and with `items_command`, `while`, `retry` of a step that fails twice |
| `ChildWorkflowsIT` | `children` | `sub-workflow` and `foreach` with `workflow_id`: child runs in the parent's log |
| `CombinationsIT` | `combo-pipeline`, `combo-approval`, `combo-failures` | the block types together, without AI. A pipeline of every block that holds a command or blocks (`shell`, a `foreach` over the lines of a command, a `switch` case, a `conditional`, a child with a `while` and a `retry`, a `compress` of the child's output, a `shell` that takes the earlier outputs as `values`): each adds a line to one file in the scratch directory, which a child run shares with its parent, and `show` names the case block and the child's steps under the steps that ran them, as `run.html` draws the child. An `approval` after a `foreach`, a child and a `switch`: the run parks, the resume runs only the last step, and `diff` against a twin run that never parked finds every step the same. A failure inside each holder with `on_failure: continue`: a `switch` case, a child after a good step, a `foreach` of children with one that fails, a `while` whose second pass fails, a `retry` of a step that times out, and a child whose `checked_command` fails, whose RED gate stays the child's until a step of the parent states it. `show` lists each child run with its status, item and gate |
| `ApprovalIT` | `approval` | `approval`: park (exit 5), `approve`, `resume`; a rejection with a comment; `cancel` of a parked run |
| `HumanInputIT` | `human-input` | `human-input`: park, `answer --value`, `resume`, the value in the next step |
| `CompressIT` | `compress` | the deterministic `compress` block |
| `GatesIT` | `gates` | `gate_status:` markers, `checked_command`, `on_failure: continue`, `--fail-on-gate` (exit 7) |
| `RegistersIT` | `examples/audit-hello` | a register across runs, without AI: the findings of a run are compared with the register, the summary says what changed, `findings` reads and verifies the register, a decision survives the next run, and the next run sees what the register knows |
| `RegisterFilesIT` | `register-files`, `combo-register` | the register folder of an audit run (`{{ register_dir }}`): the steps of the second run read what the first run left, as files mounted read-only at `/register`, with the whole evidence of each finding; no step can write the folder; `run.started`, `verify` and `show` name the head of the register and the digest of each file, and `verify` names a file that changed afterwards (exit 8). In an audit built by a loop and a child, the items of a `foreach` and the step of a child run read the same folder, and neither can write it |
| `DataDirsIT` | `mounts` | `runs_dir`, `ledger_dir` and `registers_dir` in `traceworks.json`: a container run keeps its record, its scratch directory and its ledger entry in the places the settings name; a step's mount of such a place is refused and starts nothing; a workspace that is or holds one is refused before the run is created |
| `RecordIT` | `placeholders` | the event log, manifest and ledger entry; `verify` (exit 8 after one changed byte); a copied folder; `run.html`; `show`, `runs`, `render` |
| `DiffIT` | `examples/hello` | `diff`: two runs with the same inputs in the same workspace are the same (exit 0); a changed input, or one more file in the workspace, is a difference (exit 11) that names the input and the steps, with `--outputs` as a line diff; a parked run is refused (exit 6). `runs --long` marks the inputs digest that changed |
| `SealIT` | copies of `placeholders` and `children` in the sandbox | `seal` with a key made in the sandbox; a sealed package runs in its containers and `run.started`, the manifest and `verify` name the seal; a sealed package with one more file, or with a changed child workflow, starts no run (exit 6) and the seal log has the `REFUSED` line; `require_seal: always` refuses an unsealed package; `unseal` is refused under `seal_immutable`. Needs `ssh-keygen` on the machine |
| `CommandsIT` | `examples/hello` | the commands no other test runs through the jar: `describe` prints the outline and writes it as one page without starting a run; `credentials` stores a value from a pipe, lists names only, refuses a value no run would take (exit 6) and removes a name; `watch` prints a run's events from the first one and exits with the code of the run's state |
| `IsolationIT` | `isolation` | no network by default, `network: true`; a read-only workspace, `workspace_writable: true`; a read-only package; the scratch directory |
| `MountsIT` | `mounts`, `login-mount` | declared `:ro` and `:rw` mounts, the writable one in a folder the sandbox's `writable_mounts` lists; a mount of `~/.ssh` (of the test's own fake home) is refused |
| `WritableMountsIT` | `writable-mounts`, `mounts`, `writable-workspace-git`, `writable-workspace-commit` | `writable_mounts` in `traceworks.json`: a writable mount the workflow writes out is refused before the first step (exit 6, no run) unless a listed folder holds it, one a placeholder names when its step runs (exit code 125); in a listed folder (`~/...` or a whole path) a step writes it; a listed folder lifts no other refusal, and `/` or the home directory is refused as a setting; `describe` marks the mount. A step with `workspace_writable: true` writes the files of the workspace and reads its repository, and writes nothing under the workspace's `.git` (no hook, no config, no `commondir`, no commit, no moving the `.git` away), in a repository without a `hooks` folder too; with `workspace_git_writable` in `traceworks.json` it commits, and `describe` says so |
| `SecretsIT` | `secrets`, `secrets-resume` | a secret from `credentials.json` reaches its step and is redacted in `events.jsonl`, the blobs, `run.html`, the manifest, the ledger and the output; a secret's value that stands in `workflow.json` itself runs as written after a park and a resume; a `credentials.json` modified while the run was parked warns at the resume; a file others can read, and a secret without a value, refuse the run (exit 6) |
| `SecretFormsIT` | `secrets`, `secret-astral`, `secret-broken-off` | a secret with `'` refuses the run (exit 6); a secret a shell placeholder quoted is redacted in the recorded command; a secret with a character outside the BMP is redacted in the JSON blobs (a failing `foreach`'s metadata, the checkpoint state); a step stopped at its timeout after it printed the first part of its secret keeps none of it; each in the events, blobs, `run.html`, manifest, ledger, output and `show` |
| `ImagesIT` | `package-image`, `missing-image` | a package image built by `images build`; a missing image refuses the run (exit 6) |
| `LimitsIT` | `limits` | the default memory, process and CPU limits, `container_limits`, the user, capabilities and the read-only root |
| `StopsIT` | `timeout`, `items-timeout`, `conditional-timeout`, `while-timeout`, `retry-cancel`, `output-limit`, `output-limit-setting` | a step timeout; an `items_command` timeout fails the `foreach` before the first item; a shell condition timeout fails the `conditional` (no branch) or the `while` (after its iterations so far); a `traceworks cancel` during a `retry`'s wait ends the run at once, with no further attempt; an output beyond 16 MiB (exit 153, `truncated`); `output_limit_mib: 1` in `traceworks.json` stops a step that prints 2 MiB, and `run.started` records it |
| `PauseIT` | `pause-loop`, `pause-child`, `interrupt-while`, `interrupt-retry` | real signals to the command and its process group: Ctrl-C once pauses a loop after the item in flight (exit 5) and `resume` runs every item's command exactly once (a file in the workspace counts them, and each item waits for a file of the test, so no fixed time decides); Ctrl-C twice cancels; SIGTERM leaves the run `INTERRUPTED`, removes its container, and the resume runs again only the item in flight; `traceworks pause` from another process parks the run. A pause while a child workflow's container runs lets the child end and parks the parent after its step, and the resume starts no second child; SIGTERM there leaves the run `INTERRUPTED`, and the resume runs the whole child again. SIGTERM inside the second pass of a `while`, and inside the second attempt of a `retry`: the resumed step starts at its first pass and its first attempt again, counted by a file in the scratch directory |
| `AiPauseIT` | `ai-pause-loop`, `ai-interrupt-loop` | Ctrl-C once inside a loop of calls of the fake Claude, each of which waits for a file of the test: the call in flight is finished, and after the resume the record holds exactly one `ai.request` per item. SIGTERM during a call: the run is `INTERRUPTED`, no container, proxy or network of it is left, and the resume asks once for the item that was cut and for each one after it, not for the one that had finished |
| `OutputBudgetIT` | `output-budget` | `run_output_budget_mib: 2` (with `output_limit_mib: 1`) stops a `foreach` at the item that does not fit: the step fails with the budget's note, the run ends although the step says `on_failure: continue` (exit 3), and `run.started` records the budget; the default lets the run complete; a budget below twice the output limit is refused (exit 6) |
| `ToolModeIT` | `tool-mode`, `tool-mode-twin`, `tool-mode-wrong`, `tool-mode-socket` | the tool mode of `shell` (`container_args`, no shell): literal arguments, untrusted placeholders refused, raw output and exit codes, the SARIF fact sheet (no snippet in the record, `NOT MEASURED`), `cache_dir` across two runs, a secret, no network, the read-only workspace, mounts, `mount_docker_socket` refused, a timeout and the output limit. `check` reports `container_args` without an image or as no list as errors. A package image's cache folder is per package (`tool-mode-twin`). `network: true` on a tool is not covered |
| `AiSimpleIT` | `ai-simple` | `simple` with Claude and with Pi: the prompt reaches the CLI, the answer is the step's output; `ai.request`, `step.command`, `ai.response`; `show`, `run.html` and `verify` |
| `AiModelTextIT` | `combo-model-text` | what a model wrote, across blocks: the answer of a `simple` step, which holds `$(...)`, backticks and `; command`, reaches a `shell` step as a value with every byte and runs nothing; a `simple` step in a `switch` case gets it in its prompt; a child workflow gets it as a value; the same text in a shell line and in a mount of a child, and in the `container_args` of a tool, is refused, with no command recorded and no container started, and `on_failure: continue` on the refused step lets no run past it |
| `AiChildrenIT` | `combo-ai-children` | AI calls of child runs behind the real proxy: a `simple` step, a `sub-workflow` whose child asks, and a `foreach` with `workflow_id` of two such children. Each call has its `egress` in the record under the run id of the run that made it; the proxies and the two networks carry the id of the run that was started, a child run makes none of its own, and nothing is left under any of the run ids |
| `AiToolsIT` | `ai-tools`, `ai-wrapper-tools` | each `tools` value (`none`, `read`, `write`, `shell`) reaches each CLI as the documented flags; the AI step of a `foreach` and of a `retry` carries the wrapper's `tools`, and has none without the key |
| `AiAgenticIT` | `ai-agentic` | `agentic` with Claude and with Pi: plan, action with a tool call (`ai.tool_call`), goal reached; every call carries the block's `tools`. Its approval gate (`require_approval`) is not covered: it waits inside the step and never parks the run |
| `AiCompressIT` | `ai-compress` | `compress-ai`: one call without tools gets the source and the instruction |
| `AiBranchesIT` | `ai-branches` | the AI condition of `conditional` (TRUE and FALSE, Claude and Pi, `on_condition_error`) and of `while`, and the AI step of `retry`, all with `tools: none` |
| `AiIsolationIT` | `ai-isolation` | an AI container's workspace is read-only (a write fails) unless `workspace_writable: true`; the package read-only, the scratch directory writable, the network of its provider's host only |
| `AiEgressIT` | `ai-egress`, `ai-egress-slow`, `ai-simple`, `ai-timeout` | where an AI container can connect, with the real proxy image: a target in `ai_hosts` is reached through the proxy (a socket the test opens on the machine's own address), a target nobody listed is refused with 403 and is a `DENY` in the record, in `show` and in `run.html`, and no direct connection works; without `ai_hosts` only the provider's host is listed; `ai_network: "open"` gives the default network with no proxy, as before; after a timeout and after a `traceworks cancel` of a running call, the step's container, the proxy and both networks are gone. A proxy that does not start, an exhausted address pool and Podman are not covered |
| `AiLocalModelIT` | `ai-local-model`, `ai-local-model-tools` | a `PI` step on a model server of the operator's (`local_models`), with the **real Pi CLI** of the built-in image `traceworks/pi:<hash>` and the real proxy, against a stand-in server the test starts on the machine's own address (the JDK's HTTP server, which answers the chat completions API with a fixed reply): the run completes with the stand-in's reply, the stand-in got the prompt, the record names the local backend and its URL, the proxy recorded the connection to the server and none to a provider, no provider credential exists in the sandbox; a server's secret reaches it as its key and is nowhere in the record; a model name with a colon; and a step that asks its proxy for a provider's host is denied, with the `DENY` in the record. It calls no model. It is not an `AiIntegrationTest`: it needs the built-in `pi` image, which the suite never builds (run `traceworks images build` once) |
| `AiCredentialsIT` | `ai-simple`, `ai-marker` | the fake gets exactly the fake credential of its provider (a hash it prints), no other; a credential it prints is redacted in the events, blobs, `run.html`, manifest, ledger and output; a run without a credential is refused (exit 6); the harness strips every credential name |
| `AiFailuresIT` | `ai-marker`, `ai-timeout` | a CLI's non-zero exit, a timeout (exit code 124) and a stream beyond `output_limit_mib: 1` (exit code 153) fail the AI step as documented |
| `AiGatesIT` | `ai-gates` | a `gate_status:` line in a `simple` step's output sets the gate; the output of a `retry` in AI mode sets none; `--fail-on-gate` |
| `AiModelChoiceIT` | `ai-model-choice`, `combo-model-choice` | a model choice: without a pick the default option's CLI answers with its model, `--input judge=astra` runs the Pi fake with the picked model; the start check asks for the credential of the picked option only (exit 6 without it); a pick outside the options and a model outside `allowed_models` start no run (exit 6); `run.started.model_choices`, `show`, `run.html`, `describe` and `diff` (exit 11) name the pick. A choice of a child workflow (`combo-model-choice`) is picked by the input of the run that started the child, and the start check asks for that option's credential |
| `AiImagesIT` | `ai-simple`, `ai-compress`, `ai-codex` | `ai_images`: `run.started` records it, the AI call's command names the fake image and its ID, the stderr note; a missing operator image is refused (exit 6) with its hint; invalid settings; no workflow key or environment variable sets it; Codex is refused |
| `AiWebIT` | `ai-web`, and variants it writes into its sandbox | web tools: `tools: none` with `web: search` reaches the CLI as exactly `WebSearch`, `fetch` adds `WebFetch` and the step's `web_hosts` to the call's `network_hosts`; `ai.request` records `web` and `web_hosts`, `show` and `run.html` name them; a step with web gets the foreign-content notice, and an instruction in its input turns the gate yellow; with `ai_network: "open"` the hosts are recorded and the views say they are not enforced; `check` reports and the run refuses a missing `ai.web`, web on Pi, `fetch` without hosts, hosts without `fetch`, a star mixed with names, a placeholder and `untrusted_input: false`; through `web_hosts: ["*"]` the machine's own address and `localhost:443` are `DENY`, while the same address listed by name is `ALLOW`. No web tool is ever really used: the fake shows its tool list and connects where the test says |
| `MutationTestingScriptsIT` | a package it writes into its sandbox, with the scripts of `examples/mutation-testing` | the scripts of the mutation testing audit as they ship, in the shell image, on a fixed result of PIT and fixed answers of the rating, with a real register that the scripts read from the register folder of the run (`{{ register_dir }}`): the fact sheet, the findings, a method rated low is none, the gate counts what no person decided on, a decision covers the severity it was made on (also where the finding has another severity by now), a package that did not change since it was rated is not rated again (its item is `clean`: the findings name no item) and keeps the reason of each rating in the description, the evidence is the measured fact alone, and with `decision_on_higher_severity: keep` the gate follows the register from the next run on; a register of the version before (the reason at the end of the evidence) keeps its ratings and reasons without a rating; a reason with `} -->` in it does not cut the envelope, and an entry of an answer that is no rating is left out |
| `MutationTestingGroundedScriptsIT` | a package it writes into its sandbox, with the scripts of `examples/mutation-testing-grounded`, and the callers and test classes it writes into the workspace | the scripts of the mutation testing audit that rates again from the earlier verdict and moves a rating only on a ground, in the shell image, on fixed results of PIT that change between the runs and fixed answers, with a real register read from `{{ register_dir }}`: a method whose gaps did not change is not asked about, also when the answers would move it; a changed method gets its earlier severity and reason with the mutators added or gone, and keeps its rating when the answer keeps it; a new method is rated fresh; a method that was rated low and left the findings comes back with what `resolved.jsonl` holds of it; a dismissed method is asked about once more with the note, and when it is lowered later it stays low in the next run although the register still holds its finding; a reason with `} -->` in it does not cut the envelope. And the grounds, on twenty methods: taken are a line of code in another file that calls or references the method or creates its class, a declared test that names the class, and for a raise a line of code; not taken are the method's own file, a file below `target`, a test, a file that is no Java source, a comment, a longer name, a path outside the workspace, a test method that is only called, only in a comment or a keyword, a test that does not name the class, and no ground; a lowering that is not taken is final, a raise that is not taken and a method without an answer are asked about again |
| `AiCheckIT` | the `ai-*` packages and the `combo-*` packages with an AI step | `check`: no error, and no warning but `unwired-output` |
| `CheckIT` | `broken`, `typos`, `wrong-types`, all others | `traceworks check`: errors and exit 6, and a run of such a package is refused before it exists; the commonest typos (`typos`) are findings of their own, and with `--no-check` each placeholder reaches its step as text; settings of the wrong type are errors, and a run fails the block that reads one, `FAILED` in the record, before it runs anything; every other package checks without an error or warning (but for `tool-mode`, which warns about `previous_chain_output` on purpose, `tool-mode-wrong` and `tool-mode-socket`, which `ToolModeIT` checks) |

After each test, whatever it did, `IntegrationTest` checks that no container and no network of any of its runs
(child runs included) is left, found by the label `traceworks.run`, and that every finished run passes `traceworks verify`
and its hash chain, checked by hand as docs/events.md describes it. JUnit then removes the test's temporary home,
workspace and fake user home, and fails the test if something there cannot be removed.

## AI blocks

The AI tests (`Ai*IT`, base class `AiIntegrationTest`) never call a model and never use a real credential. One
class is apart from them: `AiLocalModelIT` runs the real Pi CLI, in the built-in `pi` image as it is on the machine,
against a stand-in server of its own; it calls no model and uses no credential of a provider either, and it fails
with the start check's refusal when the image is missing.

- **Fake CLIs.** `fake-ai/claude/` and `fake-ai/pi/` hold a Dockerfile each, from the same pinned base as `images/claude`
  and `images/pi`, with a POSIX `sh` script installed under the name the adapter calls (`claude`; `pi`, which Traceworks
  runs through `sh -c`). The harness builds them once per suite run, with the suite's runtime, as
  `traceworks-it/fake-claude:<hash>` and `traceworks-it/fake-pi:<hash>`, and each test's `traceworks.json` sets
  `ai_images` to them (docs/cli.md, "AI images"). They use no network unless a test asks them to.
- **The real egress proxy.** By default an AI call of the suite runs behind the real proxy: its container is on a
  network without a way out, next to a container of the built-in image `egress` (docs/cli.md, "The network of an AI
  step"). Six classes that are not about the network leave it out ("What a run costs the host").
  `traceworks images build` would build that image together with the real `claude` and `pi` images, which the suite
  never builds. So the harness asks Traceworks for the tag: it starts one AI run without the image, the start check
  refuses it and names `traceworks/egress:<hash>`, and the harness builds exactly that tag from `images/egress` with
  the runtime's `build`. Nothing of it needs the internet: the one outside target a test reaches is a socket of the
  test itself, on the machine's own address, which a firewall between containers and the host would block.
- **What the fake does.** It reads what the adapter gives it and answers in the exact stream format the adapter parses
  (`ClaudeStream`, `PiStream`). Without a marker it answers with its arguments, the names of the credential-like variables
  it got, a hash of each credential's value, and the prompt. A marker in the prompt (or in the system prompt) picks
  another behaviour: `[fake:true]`/`[fake:false]` (an AI condition's answer), `[fake:while=N]` (TRUE for the first N calls
  of the run, counted in `/scratch`), `[fake:fail-until=N]`, `[fake:exit=N]`, `[fake:sleep=N]`, `[fake:wait=NAME]`
  (notes `fake-waiting-NAME` in `/scratch`, then waits until the test puts the file `NAME` into the workspace, two
  minutes at most), `[fake:flood]` (3 MB of output), `[fake:write]` (tries to create a file in `/workspace`),
  `[fake:print-credential]`, `[fake:gate=STATUS]`,
  `[fake:tool]` (a tool call before the answer) and, for the fake Claude only, `[fake:connect=HOST:PORT,...]` (asks
  its proxy for each target and tries each directly, and answers with what happened). A reasoning call of an `agentic` block plans one action (with
  `[fake:tool]`) and declares the goal reached after the observation.
- **No real credential.** `Traceworks` removes from the environment of every command the credential names Traceworks
  and the AI CLIs read (`ANTHROPIC_API_KEY`, `CLAUDE_CODE_OAUTH_TOKEN`, `ANTHROPIC_OAUTH_TOKEN`, `OPENAI_API_KEY`,
  `GEMINI_API_KEY`, `OPENROUTER_API_KEY`, and any name with the prefixes `ANTHROPIC_`, `CLAUDE`, `OPENAI_`, `GEMINI_`,
  `GOOGLE_`, `OPENROUTER_`, `PI_` or the suffix `_API_KEY`). Traceworks finds credentials only in `<home>/credentials.json`
  (in the test's sandbox) and in its own environment, and `user.home` points into the sandbox too. Every credential in
  the tests is an obvious fake (`sk-ant-it-fake-0123456789`); `AiCredentialsIT` compares a hash of the value the fake got
  with the fake's, so a real credential that leaked in would fail it.
- **The real AI images stay as they are.** `traceworks/claude` and `traceworks/pi` may be the real images of whoever runs
  the suite. The suite never builds, tags or removes them: each AI test class compares their image IDs before and after
  (absent is fine, changed fails), and after each test checks that no command used one of them.

## How it is built

- `Traceworks` runs the jar: `java -Duser.home=<fake home> -jar traceworks.jar <args>` in the test's workspace, with
  `TRACEWORKS_HOME`, `TRACEWORKS_ACTOR=it-tester` and a UTF-8 locale, no stdin, and a time limit. It also writes the
  home's `traceworks.json` and `credentials.json`.
- `RunFolder` reads a run folder with Jackson: the lines, the blobs, the manifest, `run.html` and the ledger entry.
- `Containers` asks the runtime's client for the containers a run left behind.
- `ItEnvironment` checks the runtime and the jar and builds the images, once per JVM; the fake AI images are built on
  the first AI test.

No test reaches into Traceworks' classes; the module has no dependency on them, only on Jackson, in test scope, so the
dependency gate is unchanged. A new case is a package directory with a `workflow.json` (and `images/<name>/Dockerfile`
when it needs an image of its own) and a class that extends `IntegrationTest`.

## CI

`.github/workflows/ci.yml` runs on every push to `main` and on every pull request. The job `build` runs `mvn -B verify` with Temurin 21:
no container. The job `integration` runs after it: it builds the jar, builds the built-in images with it
(`traceworks images build`, which the container tests of the other modules need), and runs `mvn -B -Pit verify`, once
with `TRACEWORKS_IT_RUNTIME=docker` and once with `podman`. The variable picks the runtime of this suite; the
container tests of the other modules use the runtime Traceworks detects. The Podman leg builds the built-in images a second
time, with a home that names Podman, since Traceworks takes Docker where both are installed. That leg may fail
without failing the workflow: the suite passed with rootless Podman 4.9.3 on a developer's machine on 2026-11-18, and
has not passed on the runner yet.
