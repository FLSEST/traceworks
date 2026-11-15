# Hooks

A hook is the operator's own program that Traceworks runs on the host when a register changed
(`hooks.register_changed` in `traceworks.json`, docs/cli.md). It gets the diff of the register as JSON on its
standard input: the new, resolved, reopened and still open findings, and whether the report was valid. Its exit code,
its duration and the tail of its standard error go into the register's log as `hook.ran`; a failing hook never changes
the exit code of the run.

`todo-service.sh` is an example that creates one TODO per new or reopened finding in a TODO service over HTTP
(`curl` and `jq`; `TODO_SERVICE_URL`, `TODO_SERVICE_KEY_FILE` and `TODO_SERVICE_PROJECT` from the environment).
Configure it as

```json
{"hooks": {"register_changed": ["/path/to/examples/hooks/todo-service.sh"], "timeout_seconds": 60}}
```

and export the three variables in the shell that runs Traceworks. The same shape serves GitHub Issues or Jira: read the
JSON, call an API. A workflow never configures a hook; only the operator does.
