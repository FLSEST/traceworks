# Examples

Each folder with a `workflow.json` is a workflow package. From the root of the repository, after `mvn -q package`,
build the images a package needs once, then run it:

```bash
bin/traceworks images build examples/<name>
bin/traceworks run examples/<name> --workspace <a directory>
```

`bin/traceworks describe examples/<name>` shows what a package will do before it runs, and starts nothing.

## Start here

| Package | What it shows | AI |
|---|---|---|
| [`hello`](hello/README.md) | four steps and one question: a run that parks, is approved, resumes and leaves a record you can verify | no |
| [`ai-hello`](ai-hello/README.md) | one AI step in a container, and what the record keeps of a model's call | yes: a Claude credential, and it costs money |
| [`audit-hello`](audit-hello/README.md) | the smallest audit: a register that keeps its findings across runs, and a person's decisions on them | no |

## For your own Maven project

[`mutation-testing`](mutation-testing/README.md) asks whether the tests notice when the code changes. PIT changes
the compiled code in one place at a time, and a model rates each change no test noticed. It needs tests in JUnit 5,
and you name the modules and classes to measure as inputs. On a large project a run takes hours.

[`mutation-testing-grounded`](mutation-testing-grounded/README.md) measures the same, and changes a rating only when
the model names a test or a line of code that a script finds in the workspace.

## For the operator

[`hooks/`](hooks/README.md) holds `github-issues.sh`, a program on the host that gets the diff of a register after an
audit and opens a GitHub issue for each new or reopened finding.

## How Traceworks checks itself

[`self-audit/`](self-audit/README.md) holds four real audits of this code base. They show the loop of a register at
full size: each run feeds a register, a person checks each new finding against the code and decides, and the next
run knows the decisions. They are written for this repository: their instructions, rule files and facts name its
modules, documents and threat model. Three of them call a model, from none to about 110 times in a run.

| Package | What it checks | AI | To use it on another project |
|---|---|---|---|
| [`build`](self-audit/build/README.md) | a reproducible build and the dependencies: Maven, Dockerfiles, image registries | no | a rule file of your own (`rules/build.rules`) |
| [`architecture`](self-audit/architecture/README.md) | the package graph, layer rules, direct I/O and size; a model says fine, split or move per candidate file | yes | a rule file with your packages and layers (`rules/architecture.rules`) |
| [`security`](self-audit/security/README.md) | the code against the threat model in `docs/security.md`, one verdict per area | yes | new instructions (your threat model) and new rules |
| [`security-changes`](self-audit/security-changes/README.md) | the same, for what changed since a commit and the code one call away | yes | the same as `security` |

The test modules next to them (`self-audit/tests` and `self-audit/tests-*`) test the tools and scripts of the
audits, with no container and no model; they run in every `mvn verify`.
