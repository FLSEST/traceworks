# ai-hello

A workflow with one AI step. The model may read the workspace, but not change it, and says in two sentences what it
holds. It shows how an AI step runs and what the record keeps of it.

| Block | Type | What it does |
|---|---|---|
| `describe` | `simple` | asks Claude what the project in the workspace is, with `tools: read` (Claude's `Read`, `Grep` and `Glob`) |

`tools: read` is more than the default `none`, so the workflow declares `"requires": ["ai.tools"]`. Without it the
run is refused, and `traceworks check` reports `missing-requirement`.

The block names no `model`, so the Claude CLI picks its default, which Traceworks does not know and which may change
with the CLI. The record names the model that answered (`model` in `ai.response`). For a run that should be
repeatable, name one: `"model": "claude-opus-5-5"` in the block's `config`.

No automatic test runs this example: it calls a real model, which needs a credential and costs money.

## Try it

From the root of the repository, after `mvn -q package`, with `docker` or `podman` installed:

```bash
# Once: build the images the package uses. The step runs in the "claude" image, behind the proxy of the "egress"
# image; the "shell" image is built too, as for every package.
bin/traceworks images build examples/ai-hello

bin/traceworks check examples/ai-hello      # no findings

# A credential of the provider (or set ANTHROPIC_API_KEY in the environment instead). The command asks for the
# value without showing it and stores it in ~/.traceworks/credentials.json, readable by you alone:
bin/traceworks credentials set ANTHROPIC_API_KEY

bin/traceworks run examples/ai-hello --workspace <a project directory>
```

Without a credential, `run` is refused before it starts (exit 6) and names the two names Claude takes,
`ANTHROPIC_API_KEY` and `CLAUDE_CODE_OAUTH_TOKEN`. To use Pi instead, set `"provider": "PI"` and build the images
of the package again (now with the `pi` image); Pi takes the credentials listed in [docs/cli.md](../../docs/cli.md)
("AI steps").

## With a model of your own

With a model server on your own machine or network (Ollama, llama.cpp's server, vLLM), nothing of the step goes to
a provider. Declare the server in `~/.traceworks/traceworks.json`; only the operator can:

```json
{"local_models": {"llm": {"url": "http://192.168.1.20:11434/v1", "api": "openai-completions",
                          "models": ["qwen2.5-coder:7b"]}}}
```

Then set `"provider": "PI"` and `"model": "llm/qwen2.5-coder:7b"` in the block's `config`. The run needs no credential, its
container reaches `192.168.1.20:11434` and nothing else, and the record says `local backend llm at
http://192.168.1.20:11434/v1` with the call. `localhost` is refused: a container's loopback is its own, so the
server must listen on an address of the machine. See [docs/cli.md](../../docs/cli.md) ("Local models").

## What happens

- The Claude CLI runs in the built-in `claude` image, with the prompt on stdin. The workspace is mounted read-only at
  `/workspace`, and the CLI reads no settings, hooks, `CLAUDE.md` or MCP servers from it.
- The container reaches the provider's API (`api.anthropic.com:443`) and nothing else: its only way out is a proxy
  that Traceworks starts next to it (the built-in image `egress`), and the record names every connection it asked
  for. Everything the model reads is sent to the provider.
- Containers run as the owner of the Traceworks home. When that is root, Claude refuses every tool level above
  `none`, and this step fails: run Traceworks as a normal user.
- Do not start a prompt with `/`: Claude reads a leading `/compact` or `/init` as one of its own commands, and its
  reply ("isn't available in this environment") becomes the step's answer.

## What to look at

- `events.jsonl` in the run folder: `ai.request` (the provider, the model, `tools: read`, the prompt), the CLI's
  `step.command` in the `claude` image, one `ai.tool_call` per read or search of the model, and `ai.response`
  with the answer, the tokens and the cost. See [docs/events.md](../../docs/events.md).
- `run.html` shows the prompt, each tool call with its arguments and result, and the answer.
- The credential is in none of it: the record replaces it with `[redacted:ANTHROPIC_API_KEY]` wherever it occurs.
  What the AI step can do with its credential is in [docs/security.md](../../docs/security.md).
