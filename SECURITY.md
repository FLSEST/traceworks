# Security policy

## Reporting a vulnerability

Report a vulnerability in private, through GitHub's private vulnerability reporting. Do not open a public issue
for it.

1. Open the **Security** tab of the repository.
2. Choose **Report a vulnerability**.
3. Describe the issue and send the report. Only the maintainers see it.

A useful report says:

- which guarantee does not hold, and which version or commit you tested;
- the container runtime and its version, and the operating system;
- the smallest workflow package or record that shows it, and the commands you ran;
- what you expected and what happened.

Do not put a real credential into a report. The record of a run redacts the values it knows byte for byte only, so
look through a run folder before you attach it.

## What is in scope

[docs/security.md](docs/security.md) says what the defaults of Traceworks protect and what they do not. It assumes
that a workflow package, the files it works on and what a model reads may come from someone you do not trust, and
that the operator runs Traceworks on their own machine with their own credentials.

In scope is anything that breaks a guarantee of "What the defaults protect", for example:

- a step that reads or writes outside what it may get: the Traceworks home, the operator's logins, the socket of
  the container runtime, another run;
- a step or a model that reaches the network although its block and the operator's settings do not allow it, or a
  connection of an AI step that is missing from the record;
- a secret or a credential that reaches a step that did not name it, or that stands readable in the record
  although it passed through unchanged;
- a protected value (text a model wrote, untrusted input, what a register hands a run) that ends up in the shell
  code of a step instead of in a named variable (`values`), or a shell line that names one and still runs;
- an AI step that sees the register folder of a run (`/register`), or a step that changes it or a register;
- a record, a register or a seal log that was changed and still verifies, beyond the cases the documentation
  names;
- a sealed package that changed and still runs;
- a package or a workspace that makes the Traceworks process itself run something on the host.

Not a vulnerability: what "What they do not protect" already names. Examples are a step with `network: true` that
sends its own secret away, a command that evaluates a variable it was given (`eval "$NAME"`, an unquoted `$NAME`),
what an operator's setting such as `ai_network: "open"`, `ai_images` or `--allow-host-execution` allows, and
whoever can write the home rewriting a run folder together with its ledger entry. If you think one of those
limits is wrong, or worse than the page says, a report is welcome all the same.

## Supported versions

Nothing is released yet. Fixes go to the `main` branch, and that is the only supported version.

Traceworks is tested with Docker and with rootless Podman: CI runs the whole suite with both. Rootful Podman is
not tested, so a report about it is welcome, and its fix may take longer.
