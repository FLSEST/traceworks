# Build and dependencies self-audit

Traceworks audits its own build against its rules ([docs/architecture.md](../../../docs/architecture.md): few
dependencies, only Jackson at runtime; a reproducible build; pinned images). Every step is deterministic: the audit
measures, it does not judge, so it makes no AI call and needs no credentials.

```
offline        (shell, ./images/facts, no network)  POMs and Dockerfiles against the rule file  -> build-offline.json
maven          (shell, ./images/facts, network)     dependency:tree, the runtime policy, SBOMs  -> build-maven.json, build-sbom-*.cdx.json
registries     (shell, ./images/facts, network)     newer versions in Maven Central, npm, image registries -> build-registries.json
vulns_runtime  (tool, Trivy, network)               known vulnerabilities of the runtime dependencies (SARIF fact sheet)
vulns_test     (tool, Trivy, network)               the same for the test dependencies
report         (shell, ./images/facts, no network)  the gate and the report                     -> build-report.md
```

Why it is built this way:

- **A rule replaces each judgment.** No model sorts the candidates into "finding", "intended" and "false alarm".
  Each candidate is a check with a fixed level in the report's table:
  a broken rule is RED, an update, a conflict or anything not measured is YELLOW, a note is neither. What a model
  would call "intended" is a line of the rule file (`accept`, with its reason, shown in the report) or a rule of the tool
  (a package that a `RUN` installs and removes again never reaches the image). No text of the workspace or of a
  registry reaches a gate line.
- **No AI at all.** Every question this audit asks has a measured answer: a version is pinned or not, a dependency
  is allowed or not, a registry offers a newer release or not. The one place where a judgment would help, whether
  Traceworks reaches the vulnerable code of an advisory, has no finding to judge on Traceworks today; see "Limits".
- **Facts first, network second.** `offline` reads the POMs and Dockerfiles without network and gives the inventory
  that `registries` looks up, so a run without network still checks every rule of the files and turns YELLOW for
  what it could not look up, never GREEN and never a crash.
- **Maven runs as little of the project as it can.** The audited POMs are untrusted data. The `maven` step copies only
  the reactor's `pom.xml` files (no sources, no `.mvn`), runs the one goal `dependency:tree` of a pinned plugin with no
  lifecycle phase (so no plugin the POMs bind runs), checks every checksum (`-C`), sends every repository to the one of
  the rule file (a mirror of `*`), and refuses to start when a POM declares a build extension, which Maven would load.
  Its local repository is a fresh folder of the container's `/tmp`, deleted at the end, never the scratch directory
  that the record keeps.
- **The scanner scans what Maven resolved.** Trivy reads the SBOMs the `maven` step writes, so it needs no access to
  Maven Central of its own; its network is only for its vulnerability database, which its cache folder keeps.

## Run it

From the root of a Traceworks checkout, which is the workspace the audit reads:

```bash
traceworks images build examples/self-audit/build    # once: the shell and facts images, and pulls Trivy
traceworks check examples/self-audit/build
traceworks run examples/self-audit/build
```

The `maven`, `registries` and `vulns_*` steps need network (`network: true`), all read-only: Maven Central and the npm
registry, the image registries the rule file names (Docker Hub, `registry-1.docker.io` with its token host
`auth.docker.io`, for the bases), and `mirror.gcr.io` for Trivy's database. On Traceworks a run
takes under a minute: `maven` about 13 s with a local repository of 18 MiB and a peak of about 200 MiB of memory,
`registries` about 5 s for 34 requests. Trivy's first run downloads its database into
`<home>/cache/tools/aquasec_trivy` (1.7 GB unpacked), which later runs reuse.

The run leaves its results in the scratch directory, and the record keeps them:

| File | What it holds |
|---|---|
| `build-offline.json` | the facts of the POMs and Dockerfiles, every item a check found, and the inventory of versions to look up |
| `build-maven.json` | the facts of the resolved dependencies: the runtime policy, conflicts, managed downgrades, Maven's time and repository size |
| `build-registries.json` | every newer release found, and every lookup that failed with its reason |
| `build-sbom-runtime.cdx.json`, `build-sbom-test.cdx.json` | the resolved dependencies outside and inside the test scope, as CycloneDX 1.5 |
| `build-report.md` | the gate, every fact, the broken rules, the items to look at, the notes and what was not measured |

**The gate:** RED for a broken rule: a dependency outside the test scope that the rule file does not allow (declared
or resolved), a module without the enforcer's guard or a guard that allows something else, a version that is not
pinned (a range, `LATEST`, `RELEASE`, a snapshot of another project, a dependency or plugin without a version, a
plugin of `plugin-pinned` without a version in any POM), a repository over http, a module without
`project.build.outputTimestamp` or with another Java release, a base image without a digest, a package installed
without a version, a download without a checksum, or a high or critical vulnerability outside the test scope.
YELLOW for a newer release of a dependency, a plugin, an npm package or a base image's tag, a version conflict, a
version the dependency management lowers, any other vulnerability, a POM or Dockerfile not read, an `accept` line that
matches nothing, and anything a step could not measure. GREEN otherwise.

## What is measured

- **POMs** (`offline`): the reactor from the root `pom.xml` and the modules it names (in profiles too; a module
  outside the workspace or behind a symbolic link is not followed). Each declared dependency with its scope (its own,
  its dependency management's, else compile), each explicit version with the properties of the reactor resolved,
  each plugin with its version or one from the plugin management of the reactor, repositories, build extensions, the
  Java release (`maven.compiler.release` or the compiler's `release`), `project.build.outputTimestamp`, and the
  enforcer's `bannedDependencies`. What an imported BOM manages is not visible offline; the `maven` step resolves it.
- **Dockerfiles** (`offline`): the files the rule file names. The base image of each stage (a stage name and `scratch`
  need no digest; `COPY --from=<image>` counts as a base), the packages that `apt-get`, `apk`, `npm -g` and `pip`
  install, and every download (`curl`, `wget`, `ADD <url>`). A package installed and removed in the same `RUN` is
  build-only. A download is verified when the same `RUN` checks a checksum (`sha256sum -c` and the like) or a
  signature (`gpg --verify`), or `ADD` has `--checksum`. An npm package needs an exact version. Variables come from
  `ARG`, `ENV` and assignments earlier in the same `RUN`; a here-document is reported as not read.
- **Resolved dependencies** (`maven`): `dependency:tree -Dverbose` of `maven-dependency-plugin` 3.11.0. Every
  artifact in a scope other than test that the rule file does not allow, with the chain that brings it; every node
  Maven left out for a conflict; every version the dependency management lowers.
- **Newer versions** (`registries`): each explicit version of a dependency, an imported BOM or a plugin against its
  `maven-metadata.xml`, each npm package against its registry document (latest version, the releases in between and
  their dates), and each base image's tag against its registry (the digest the tag points to now; a pin of the tag's
  platform manifest counts as current). A release is a version that matches `stable-version`. Every answer is
  bounded in size and parsed strictly, and only versions, dates and digests of their expected form reach the facts.
  The rule file, not the audited project, decides which hosts are asked: a base image on a registry that no
  `image-registry` line names is not contacted at all and is not measured, with the line that would allow it. A
  registry's token address must be the registry itself, the token host of its line, or `auth.docker.io` for Docker
  Hub, and no lookup follows a redirect to another scheme, host or port.
- **Vulnerabilities** (`vulns_runtime`, `vulns_test`): Trivy over the two SBOMs, in tool mode with a SARIF fact sheet
  (docs/cli.md, "Tool mode of the shell block"). A vulnerability outside the test scope that Trivy rates high or
  critical is RED; any other is YELLOW, a test-only one with the note that it is not in the built artifact.

## The rule file

`rules/build.rules` names what must hold and where the tool looks, so the package works on another Maven project with
another rule file. A line is a keyword and its values; blank lines and lines starting with `#` are ignored, and an
error is refused with its line number.

| Line | Meaning |
|---|---|
| `runtime-allow <groupId>:<artifactId>` | allowed outside the test scope; `*` matches any run of characters. The modules of the reactor are always allowed |
| `require enforcer-guard` | every module inherits a `bannedDependencies` rule of `maven-enforcer-plugin` that bans the compile, runtime, provided and system scopes and includes exactly the `runtime-allow` lines |
| `require output-timestamp` | every module that builds an artifact sets `project.build.outputTimestamp` |
| `java-release <n>` | every module that compiles is built for this release |
| `plugin-pinned <artifactId>...` | these plugins have a version in some POM, so the Maven that runs the build cannot choose it |
| `stable-version <regex>` | which versions count as releases (default `^\d+(\.\d+)*$`) |
| `maven-repository <url>` | the one repository of the `maven` step and of the lookups (default Maven Central) |
| `npm-registry <url>` | the npm registry of the lookups (default `https://registry.npmjs.org`) |
| `image-registry <host> [<token-host>]` | an image registry the lookups may ask for a tag's digest, as a host name with an optional port; repeatable. Without the line only Docker Hub (`docker.io`, also written `index.docker.io` or `registry-1.docker.io`), whose token host `auth.docker.io` is known; any line replaces that default. `<token-host>` is where the registry may send for an anonymous token when it is not the registry itself, such as `gitlab.com` for `registry.gitlab.com` |
| `maven-extensions allow` | let the `maven` step load the build extensions the POMs declare |
| `dockerfile <glob>` | where Dockerfiles are, relative to the workspace (default `**/Dockerfile`) |
| `accept <check> <file> <reason>` | the items of `package_unpinned` or `download_unverified` in that Dockerfile are so on purpose; the report lists them with the reason |

A URL is https, or http to a loopback address (a test's local registry). An image registry on `localhost` or
`127.0.0.1` is asked over http, every other over https.

## Limits

- **Whether Traceworks reaches a vulnerable function is not judged.** A vulnerability is the scanner's entry for a
  resolved version. Judging an advisory against the code is the one place where an AI would help, and it is left out:
  the start check of a run asks for the credentials (and the image) of every AI provider a workflow names, also for a
  step that `run_if` would skip, so an AI step in this workflow would make every run need credentials, findings or
  not. A triage of the vulnerabilities belongs in a workflow of its own; on Traceworks there is nothing to triage
  today.
- **The images are read as Dockerfiles, not as images.** The npm dependencies of a pinned CLI and the Debian packages
  of an image resolve when the image is built; nothing here scans an image's contents.
- **An update is a version number.** What changed in it, and whether the code that reads a CLI's output
  (`PiCli`, `ClaudeCli` and their stream readers) still fits, is for whoever raises the version.
- **A parent POM outside the reactor and an imported BOM are seen only by Maven.** The offline checks cover the POMs of
  the workspace; a version that only a remote parent or a BOM pins is resolved by the `maven` step and checked against
  the runtime policy there, not for pinning.
- **The lexical reading of a Dockerfile** knows the common package managers and download tools. A package installed by
  a script the Dockerfile copies in, or a download through another tool, is not seen.
- **Maven Central rate-limits.** A lookup that gets `429` is not measured, with the wait the repository asks for; the
  gate is then YELLOW.

## Tests

The tool's tests are in [`examples/self-audit/tests-build`](../tests-build), a module of the Maven build, so `mvn
verify` runs them; they use no network and no container: the registries are the JDK's HTTP server on the loopback
address, and Maven is a fake `mvn`. One of them runs the package's shape through the real engine as a host run (it
needs `bash`); on its own, run the module with `-am`: `mvn -pl examples/self-audit/tests-build -am test`.
