# Traceworks

Repeatable, traceable workflows – deterministic steps, optional AI with any model, tools in containers.

Traceworks is a command line tool that runs workflows. A workflow is a package: a directory of plain files with a
`workflow.json`, its scripts, its rule files and its images. Its steps are deterministic, and a model is asked only
where a step says so. Every step runs in its own container. A run leaves a hash-chained record in plain files that
anyone can check afterwards. There is no database and no service: the tool runs a workflow, writes what happened,
and exits.

It is for work that has to be done the same way again and shown afterwards: checks, audits and reviews of a
repository, with or without a model, where "what ran, with which inputs, and who decided what" needs an answer.

## The idea

**A package says what is done. The tool gives the frame.** What a check knows lives in the package, so a package
changes without a change of the tool. The frame is the same for every package: Traceworks checks the package, runs
its steps in containers, records what happened, and keeps what an audit knows across its runs in a register.

Two pages explain it with drawings:

- [docs/features.html](docs/features.html): the key features, each by the same four questions: what goes wrong
  without it, what Traceworks does, where you see it, and what it does not do.
- [docs/audit-loop.html](docs/audit-loop.html): the register, round by round: an audit that knows when it is done.

Both are self-contained pages that load nothing from the network. A repository host shows HTML as source, so open
them in a browser from a checkout.

## Quick start

Nothing is released yet: there is no download, no artifact in a Maven repository and no image in a registry. The
way to get Traceworks is to build it from the sources.

You need Java 21, Maven, git, `bash`, and `docker` or `podman`. The first build downloads the Maven plugins and
Jackson, and the first image build downloads a Debian base image and packages, so both need the network once.

```bash
# The jar: traceworks-cli/target/traceworks.jar. bin/traceworks starts it with java -jar.
mvn -q package -DskipTests

# Once: the image the example's steps run in (the built-in "shell" image).
bin/traceworks images build examples/hello

# Before the run: what the package will do, and what a run would refuse. Both start nothing.
bin/traceworks describe examples/hello
bin/traceworks check examples/hello

# The run. It has four blocks, no AI, and asks one question. With --no-input it does not ask on the
# terminal: it parks at the question and exits with code 5.
bin/traceworks run examples/hello --no-input

# Use the run id that was printed; a prefix of four characters is enough.
bin/traceworks approve <run> --answer Yes
bin/traceworks resume <run> --no-input     # runs the last block, exits 0

# The result.
bin/traceworks show <run>                  # status, the workflow, every step, the gate
bin/traceworks render <run>                # prints the path of run.html: open it in a browser
bin/traceworks verify <run>                # the chain, the manifest, the ledger entry: exits 8 if one does not hold
```

Leave out `--no-input` in a terminal and the run asks the question there instead of parking.

The steps see the directory you start the run in as their workspace, read-only, and have no network. The record is
in `~/.traceworks/runs/<yyyy-mm-dd>/<run-id>/`: `events.jsonl`, `blobs/`, `manifest.json` and `run.html`.
`~/.traceworks` is the Traceworks home; `--home` or `TRACEWORKS_HOME` moves it. Change one byte of `events.jsonl`
and `verify` exits with code 8.

[examples/hello](examples/hello/README.md) explains the example, and [docs/cli.md](docs/cli.md) every command and
exit code. On a machine where no container runtime works, a run can go to the host with
`--allow-host-execution`; that is a fallback, and a shell command there can do what you can do
([docs/cli.md](docs/cli.md#host-execution)).

## What it gives

The names are those of [docs/features.html](docs/features.html), where each has its limit written next to it.

| Feature | In one line | Documented in |
|---|---|---|
| Definition independent of the tool | A workflow with its scripts, rules and images is a directory of plain files; the tool reads it and names it by a digest | [docs/cli.md](docs/cli.md#describe), [examples/hello](examples/hello/README.md) |
| Defined once, used again | The same package runs on any workspace, or on a new checkout of a git repository at one commit (`--workspace`, `--source`) | [docs/cli.md](docs/cli.md#isolated-workspace) |
| Repeatable | A run records the package digest, its inputs, every image ID and the setup of every AI call; `traceworks diff` says whether a second run reproduced the first | [docs/cli.md](docs/cli.md#diff) |
| Sealed | `traceworks seal` signs a digest of the package with an SSH key; a sealed package that changed is refused | [docs/cli.md](docs/cli.md#seal-unseal-and-seals) |
| Traceable | The record is an append-only event log in which each line holds the hash of the line before; outputs are blobs named by their SHA-256; a manifest and a git ledger name the last line | [docs/events.md](docs/events.md) |
| Visible | `describe` shows a workflow before it runs; `run.html` shows the run afterwards, as one static file without scripts | [docs/cli.md](docs/cli.md#describe) |
| Deterministic first, AI optional | A workflow works without any AI: shell steps, tool steps, loops, conditions and questions to a person; no default calls a model | [docs/cli.md](docs/cli.md#ai-steps) |
| The model, chosen per step | A block names its provider and its model; the record names the model that was asked for and the one that answered | [docs/cli.md](docs/cli.md#ai-steps) |
| Contained | One container per step: read-only root file system, no capabilities, resource limits, no network and a read-only workspace unless the block says otherwise | [docs/security.md](docs/security.md#what-the-defaults-protect) |
| Any tool, in a container | A step runs whatever tool an image holds; SARIF output becomes facts that later steps and the register can use | [docs/cli.md](docs/cli.md#tool-mode-of-the-shell-block) |
| Secrets protected | A step gets only the secrets it names; every value is redacted in the record and in what the tool prints | [docs/cli.md](docs/cli.md#secrets), [docs/events.md](docs/events.md#redaction) |
| Controllable | Pause, resume and cancel; approval and input blocks where a person must decide; gates as `GREEN`, `YELLOW` or `RED` | [docs/cli.md](docs/cli.md#interactive-and-parked-runs) |
| The register | What an audit knows across its runs: findings with an identity, a person's decisions, reused verdicts, in a hash-chained log | [docs/registers.md](docs/registers.md) |
| Lean | One runnable jar with one runtime library; plain files in one directory; no database, no service | [docs/architecture.md](docs/architecture.md#dependencies) |

## AI steps

AI is optional. `examples/hello` and `examples/audit-hello` call no model, and no setting makes a workflow call one
that its blocks do not name.

- **Two command line tools** do the calls, each in its own built-in image: Claude Code (provider `CLAUDE`) and Pi
  (provider `PI`), which reaches the backend its model names. Any other provider is refused, never replaced.
- **Tools** are set per block: `none` (the default: the model only answers), `read`, `write` or `shell`.
- **Credentials** come from `credentials.json` in the home, written by `traceworks credentials set <NAME>`, or from
  the environment. Claude takes `ANTHROPIC_API_KEY` or `CLAUDE_CODE_OAUTH_TOKEN`; the names Pi takes are in
  [docs/cli.md](docs/cli.md#ai-steps). Without a credential the run is refused before it starts. The credential
  goes to the provider's containers only and is redacted in the record.
- **The network:** the container of an AI step reaches the API of its provider and nothing else. Its only way out
  is an allowlist proxy that Traceworks starts next to it, and the record names every connection it asked for,
  allowed or denied ([docs/cli.md](docs/cli.md#the-network-of-an-ai-step)). Everything a model reads is sent to
  its provider.
- **The record** keeps the prompt, every tool call and the answer, with tokens and cost.

[examples/ai-hello](examples/ai-hello/README.md) is one AI step with read-only tools. It calls a real model, so it
needs a credential and costs money. What an AI step can still do is in
[docs/security.md](docs/security.md#ai-steps).

## What a model's verdict is

A deterministic step gives the same result for the same input. A model does not. Two full runs of the testing audit
on the same code measured the same 637 methods with a gap in their tests, and rated 63 of them differently. So a
model's verdict is a testimony, not a measurement, and Traceworks asks of it what one asks of a witness:

- **It is on record.** The prompt and the answer are in the run record and cannot be changed afterwards.
- **It can be checked.** A finding names its evidence, and a deterministic step compares it with what the run
  measured.
- **A person answers for the decision.** The model proposes; a person accepts or dismisses in the register.

Letting a model go over the same thing again does not converge by itself: every run finds something. A loop over a
model's verdicts ends when the workflow keeps to a few rules. They are decisions of the workflow's author, and the
tool gives the means: a defined workflow for control, a register as the memory, a loop for the improvement.

1. **A measurement says what there is.** The model orders a finite list. Where a tool can measure the whole set,
   the model adds nothing to it.
2. **A verdict is given once per state.** What did not change is not judged again.
3. **A verdict changes for a reason:** the thing changed, a person decided, or the author asks for a new judgment,
   for example by a stronger model.
4. **From the severe to the less relevant, down to a floor a person sets.** Below it nothing is reported. The
   lower the floor, the more a model judges at a border of discretion, and the more each run refills what the last
   one emptied.

[examples/audit/testing](examples/audit/testing/README.md) is built on these rules: PIT measures, a model rates each
method once, and the gate counts what no person has decided on.

## The self-audits

Traceworks checks itself with its own packages: `security`, `architecture`, `build`, `functionality` and `testing`
under [examples/self-audit](examples/self-audit/README.md). They share one shape: a script measures and chooses, a
model judges one item per call, and a script checks every quote, counts and sets the gate; `build` asks no model at
all. The four that ask a model each feed a register, so the next run knows what was found and decided and asks only
about what changed, was never judged or is still open.

[examples/audit-hello](examples/audit-hello/README.md) is the smallest audit with a register: three shell steps, no
AI.

## Status

The version is `0.1.0-SNAPSHOT`. There is no release.

**Built:** the engine and its block types, the check of a package (`check`, `describe`), container runs with
Docker or Podman, the four built-in images (`shell`, `claude`, `pi`, `egress`), the run record with `verify`,
`show`, `watch`, `run.html` and `diff`, pause and resume, secrets and their redaction, AI steps with Claude Code
and Pi behind the allowlist proxy, seals, registers with `traceworks findings`, and the five self-audit packages.

**Not built:**

- Signatures of the record. A manifest, an approval in a run and a person's decision in a register are not signed.
  The record is tamper-evident, not signed; only a package can be signed, by its seal.
- A server for MCP clients, and MCP servers for AI steps.
- An export of a workflow to BPMN.
- A scheduler, a web page to click in, a live view of a run. A run starts when a person, a cron entry or a CI job
  starts it.

**Verified with:**

- Docker 29.1.3. Podman is supported by the code and untested: CI runs the integration tests with it, and that leg
  may fail without failing the workflow.
- Linux. CI runs on Linux only. Where the system has no `setsid` program (macOS), Ctrl-C cancels a run instead of
  pausing it.
- Stand-in command line tools in place of the models, in every automatic test. No test calls a model or uses a
  real credential. The web tools of an AI step are tested this way only.

[docs/security.md](docs/security.md) says what the defaults protect and what they do not. Read it before you run a
package you did not write: a package is code you run.

## Documentation

| File | What it holds |
|---|---|
| [docs/features.html](docs/features.html) | the key features, with drawings (open in a browser) |
| [docs/audit-loop.html](docs/audit-loop.html) | the register and the audit loop, to step through (open in a browser) |
| [docs/cli.md](docs/cli.md) | every command, flag and exit code; the home and `traceworks.json`; AI steps, secrets, images, seals |
| [docs/events.md](docs/events.md) | the run record: the run folder, the hash chain and how to check it by hand, every event type, the manifest, the ledger |
| [docs/registers.md](docs/registers.md) | registers: audit workflows, findings across runs, items, decisions, hooks |
| [docs/security.md](docs/security.md) | what the defaults protect and what they do not |
| [docs/architecture.md](docs/architecture.md) | modules, ports and adapters, layers, the life of a run, the dependencies and their reasons, building and testing |
| [docs/compatibility.md](docs/compatibility.md) | workflows follow the format of an earlier system; this page lists where Traceworks deliberately behaves differently and what it refuses |
| [examples/](examples) | `hello`, `ai-hello`, `audit-hello`, `hooks` and the self-audits, each with a README |
| [integration-tests/README.md](integration-tests/README.md) | the black-box suite and what a run of it costs the host |

## Building and testing

Containers start only on purpose. Three commands, from the root of the repository:

| Command | What runs | What it starts |
|---|---|---|
| `mvn verify` | the unit and component tests of every module, and the jar | no container, no image build |
| `mvn -Pcontainers verify` | the same, plus the container tests of `traceworks-adapters` and `traceworks-cli` | real containers through `docker` or `podman` |
| `mvn -Pit verify` | everything: the same as `-Pcontainers`, plus the suite of `integration-tests` | the same, plus the containers, images and networks of the suite |

The two profiles need the built-in images, and no test builds them: run
`java -jar traceworks-cli/target/traceworks.jar images build` once after `mvn verify`, and again after a change
below `images/`. With a profile on, a missing runtime or image fails the test that needs it; nothing is skipped.
The details are in [docs/architecture.md](docs/architecture.md#building-and-testing) and
[integration-tests/README.md](integration-tests/README.md); [CONTRIBUTING.md](CONTRIBUTING.md) says how to
contribute, and [SECURITY.md](SECURITY.md) how to report a vulnerability.

## License

GPL-3.0-only. See [LICENSE](LICENSE).
