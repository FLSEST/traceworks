# Hooks

A hook is the operator's own program that Traceworks runs on the host when a register changed
(`hooks.register_changed` in `traceworks.json`, docs/cli.md). It gets the diff of the register as JSON on its
standard input: the new, resolved, reopened and still open findings, and whether the report was valid
([docs/registers.md](../../docs/registers.md), "Hooks"). Its exit code, its duration and the tail of its standard
error go into the register's log as `hook.ran`; a failing hook never changes the exit code of the run.

`github-issues.sh` opens one GitHub issue per new or reopened finding, with the GitHub CLI (`gh issue create`), and
says on standard error how many findings were resolved; it closes no issue. It needs `gh` and `jq`, and `gh` logged
in (`gh auth login`, or `GH_TOKEN` in the environment). The hook runs in the register's folder, which is no clone,
so name the repository, as its argument or as `GH_REPO`:

```json
{"hooks": {"register_changed": ["/path/to/examples/hooks/github-issues.sh", "owner/repo"], "timeout_seconds": 60}}
```

Without `gh`, without `jq` or without a repository it says so, opens no issue and exits with 2. A report the
register rejected opens none either. A finding that is reopened later opens a new issue.

To try it without GitHub, put a stand-in for `gh` first on the `PATH` and feed it a diff by hand, as
`GithubIssuesHookTest` in `traceworks-cli` does. For another tracker, keep the shape: read the JSON, call its API. A
workflow never configures a hook; only the operator does.
