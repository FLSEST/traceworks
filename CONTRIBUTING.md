# Contributing

Traceworks is small on purpose. A change is welcome when it keeps the tool so: repeatable, traceable, with few
dependencies.

A vulnerability is not reported in an issue: see [SECURITY.md](SECURITY.md).

## Build and test

You need Java 21, Maven, git and `python3` (the tests of two self-audit packages run their scripts with it). The
container tests also need `docker` or `podman`.

```bash
mvn verify                                                    # unit and component tests, and the jar; no container
java -jar traceworks-cli/target/traceworks.jar images build   # once, and after a change below images/
mvn -Pcontainers verify                                       # plus the container tests of the adapters and the command line
mvn -Pit verify                                               # everything, with the black-box suite of integration-tests/
```

- `mvn verify` starts no container and builds no image.
- With `-Pcontainers` or `-Pit`, a missing runtime or an image that is not built fails the test that needs it.
  Nothing is skipped.
- One class: `mvn -Pit verify -pl integration-tests -Dtest=SecretsIT`, after a full build.

The details are in [docs/architecture.md](docs/architecture.md#building-and-testing) and
[integration-tests/README.md](integration-tests/README.md).

### What a full run costs the host

`mvn -Pit verify` is not a light command. Measured with Docker 29, one run created about 830 containers and 40
networks, 257 network devices on the host in all, and took about 13 minutes
([integration-tests/README.md](integration-tests/README.md), "What a run costs the host"). On Linux every network
is a bridge and every connection of a container a device pair, and the network services of a desktop react to
each. Two container-starting builds at once multiply that, so start one when the other has finished.
`scripts/container-footprint.sh mvn -Pit verify` prints what a run made the runtime create and what it left behind.

CI runs `mvn verify` and then `mvn -Pit verify` on every push and pull request, so a change that touches no
container code does not need the full run on your machine.

## Rules

- **Everything in the repository is English:** code, comments, documentation, test names, messages. The one
  exception is the class `core.compat.GermanWords`, which holds the German words that workflows of the earlier system
  use, so that those workflows keep working.
- **Commits** are small and follow [Conventional Commits](https://www.conventionalcommits.org/): `fix(register):
  ...`, `feat: ...`, `docs: ...`, `test: ...`.
- **Tests first.** A fix starts with a test that fails without it. A feature comes with its tests, and a test that
  starts a container, builds or pulls an image or creates a network carries `@Tag("container")`; a guard test
  fails the build when the tag is missing.
- **No flaky tests.** A test that fails now and then is fixed or removed, not repeated until it passes.
- **No real AI call and no real credential in a test.** AI steps are tested against stand-in command line tools
  (`integration-tests/fake-ai/`), and every secret in a test is made up.
- **Dependencies:** the only runtime library is Jackson, and the build fails on any other. A new dependency of any
  kind, a build plugin or an external program included, needs a written reason in
  [docs/architecture.md](docs/architecture.md#dependencies).
- **Documentation with the change.** What a command does, prints or exits with is in `docs/`; a change of
  behaviour changes the page in the same commit. A limit is written down next to the feature it belongs to.
- **Compatibility:** a workflow behaves as [docs/compatibility.md](docs/compatibility.md) says, or it is refused.
  It never silently behaves differently.

## Secret scan

Turn the hook on once per clone:

```bash
git config core.hooksPath scripts/hooks
```

`scripts/hooks/pre-commit` runs [gitleaks](https://github.com/gitleaks/gitleaks) with `.gitleaks.toml` on the
staged changes and refuses the commit when it finds something, and also when gitleaks is not installed. CI scans
the whole history with the same rules.

A real secret is never allowed in `.gitleaks.toml`: remove it and rotate it. A made-up value of a test gets a name
that does not read like a key, or an entry in `.gitleaks.toml` that names its exact value below `src/test/`.

## License

Traceworks is licensed under GPL-3.0-only ([LICENSE](LICENSE)). A contribution is accepted under the same license:
by sending one you say that you have the right to do so. There is no contributor license agreement.
