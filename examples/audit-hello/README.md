# audit-hello

The smallest audit: three shell steps, no AI, and a register that keeps the findings across runs
([docs/registers.md](../../docs/registers.md)). It reports every file in the workspace with more than ten lines.
A register is kept per audit and per project, so every run names the project it works on.

| Block | Type | What it does |
|---|---|---|
| `known` | `shell` | prints `{{ audit_findings }}`, what the register knows before this run: `(no known findings)` the first time |
| `measure` | `shell` | runs `scripts/measure.sh`: counts the files, finds the long ones, and prints a fact sheet (`## FACT SHEET`, `FACT files:`, `FACT long_files:`, one `HIT long_file:` per long file) |
| `report` | `shell` | runs `scripts/report.sh`: a short markdown, the gate (`YELLOW` with a long file, else `GREEN`), and the audit result envelope with one finding per long file, written with `jq` |

`workflow.json` declares the audit:

```json
"audit": {"enabled": true, "key": "hello", "category": "example", "report_block": "report"}
```

and `catalog.json` names its one rule, `long-file`, with the severities it may state. The evidence of every finding
names the fact `long_file` of the measure step, so the register marks none as unverified.

## Try it

From the root of the repository, after `mvn -q package` and `bin/traceworks images build examples/audit-hello`
(the shell image), in a directory with a few files:

```bash
bin/traceworks run examples/audit-hello --workspace /some/dir --project some-dir
#   ...
#   register hello of some-dir: 2 new, 0 resolved, 0 reopened, 2 open now

bin/traceworks findings                     # hello  some-dir  example  OPEN 2
bin/traceworks findings hello list          # one line per finding: id, status, severity, rule, subject, title
bin/traceworks findings hello show <id>
bin/traceworks findings hello accept <id> --note "that file is long on purpose"

# Shorten one file, lengthen another, and run again:
bin/traceworks run examples/audit-hello --workspace /some/dir --project some-dir
#   register hello of some-dir: 1 new, 1 resolved, 0 reopened, 1 open now
```

`--project some-dir` is your word for what the run works on; without a project the run is refused before it
starts. The `known` step of the second run prints the findings of the first, the accepted one with its note. Open
`<home>/registers/hello/some-dir/report.html` for the page, and `bin/traceworks findings hello verify` checks the
register's chain and its anchors in the ledger.

Run it on another directory with another key (`--workspace /other/dir --project other-dir`) and the home holds two
registers of `hello`: the second run knows nothing of the first directory's findings and resolves none of them.
`bin/traceworks findings` then lists both, and a command on one of them names it: `bin/traceworks findings hello
list --project some-dir`.

## What to look at

- `<home>/registers/hello/some-dir/events.jsonl`: `register.created`, which names the project, then per run
  `report.applied` and one line per finding that is new, resolved or reopened, and the decisions you took. See "The
  register log" in [docs/events.md](../../docs/events.md).
- `<home>/ledger/anchors/hello/some-dir.jsonl` in the ledger, the one git repository of the home: one line per
  write of the register, with the head of its log (`git -C <home>/ledger log --oneline` shows the commits of the
  runs and of the anchors).
- The envelope at the end of the `report` step's output, in the run's record and in `run.html`.
- `RegistersIT` in `integration-tests` runs this sequence against the jar, and `AuditHelloReportTest` in
  `traceworks-cli` holds the report to the same bytes for a fixed list of files, with `jq` alone on the `PATH`.
