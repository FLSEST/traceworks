# hello

A small workflow with four blocks. It runs without any AI and shows the whole life of a run: it parks at a
question, takes an answer, resumes, and leaves a record you can verify.

| Block | Type | What it does |
|---|---|---|
| `greet` | `shell` | prints `Hello, world` (the name comes from `defaultContext`; override it with `--input name=Anna`) |
| `count` | `shell` | runs `scripts/count-files.sh`, which counts the files in the workspace and reports a green gate |
| `publish` | `approval` | asks `Publish the greeting "Hello, world"?` with the options `Yes` and `No` |
| `done` | `shell` | prints `Published: Hello, world` |

The workflow sets `metadata.gate.expected: true`, so a run must report a gate. `count` does: it prints
`gate_status: GREEN`.

## Try it

From the root of the repository, after `mvn -q package`, with `podman` or `docker` installed:

```bash
# Once: build the images the example uses. Every step of the run runs in the "shell" image.
bin/traceworks images build examples/hello

bin/traceworks check examples/hello

# No terminal question: the run parks at "publish" and exits with code 5.
bin/traceworks run examples/hello --no-input

# Use the run id that was printed (a prefix of four characters is enough).
bin/traceworks approve <run> --answer Yes
bin/traceworks resume <run> --no-input   # runs "done", exits 0
bin/traceworks verify <run>              # chain, manifest and ledger: ok
```

Leave out `--no-input` in a terminal and the run asks the question there instead of parking.

In the container, the workspace (the directory you run it from) is `/workspace`, read-only, and the package is
`/package`, so `count` runs `bash "/package/scripts/count-files.sh"` in `/workspace`. On a machine where no container
runtime works, add `--allow-host-execution` to `run` and `resume`: the steps then run on the host with `bash`, and
`package_dir` is the package's directory there. The flag, and `allow_host_execution` in `traceworks.json`, are only
that fallback: where `podman` or `docker` works, the run runs in containers anyway. The first line of `run` says
which: `in containers (docker 29.1.3)` or `on the host (no container runtime works)`.

Open the `run.html` that the commands print to see the run in a browser.

## What to look at

- `events.jsonl` in the run folder shows the question, the park, the answer and the resume. See
  [docs/events.md](../../docs/events.md).
- Where each step ran is in the record too: `run.started` says `"execution": "container"` and names the container
  runtime, every `step.command` names its image (`shell`), the image ID it started from and its mounts, and
  `manifest.json` lists the image under `images`. No step has network.
- Change one byte of `events.jsonl` and run `traceworks verify <run>` again: it exits with code 8.
- [docs/cli.md](../../docs/cli.md) explains every command and exit code.

## Where to go next

- `examples/audit-hello`: the same size, and an audit: its findings go into a register that keeps them across runs
  ([docs/registers.md](../../docs/registers.md)).
- `examples/ai-hello`: one AI step, with Claude or Pi in a container.
- `examples/self-audit/`: the audits Traceworks runs on itself.

The same sequence runs as tests in `traceworks-cli`: `HelloExampleTest` as a host run, in every build, and
`HelloContainerTest` in containers, only with `mvn -Pcontainers verify` or `mvn -Pit verify`, where it fails when no
runtime works or the `shell` image is not built (docs/architecture.md, "Building and testing").
