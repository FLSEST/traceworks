# The image of the Pi AI provider: its command line tool on a pinned Node.js base.
# Pinned are the base image, by its digest, and Pi, ripgrep and fd, by their versions. apt and npm resolve their
# dependencies when the image is built, so a build on another day can differ in them.
FROM node:22-slim@sha256:4b150b48b4116e2a22f2c39cc27025fbf4f153e2078fd3b14d159fdb5c7279da
LABEL org.opencontainers.image.description="the Pi coding agent 1.5.2 with ripgrep and fd on Node.js 22: runs an AI step of the PI provider"

# Pi's grep and find tools run rg and fd. Traceworks starts Pi offline, so Pi cannot download them itself.
# rg is Debian's. fd is a release of fd's own, since Debian's fd 8.6.0 does not know --no-require-git, which Pi's
# find passes (fd added it in 8.7.0). It is 10.5.0, released 2026-08-26: the newest release that was at least 14 days
# old when it was pinned, so it has had time to be found bad. The asset is the build for this glibc base,
# fd-v<version>-<arch>-unknown-linux-gnu.tar.gz, for amd64 and arm64 only (Pi 1.5.2 itself would download the musl
# build).
# The release publishes no checksum file. Each sha256 below is the digest GitHub publishes for the asset
# (https://api.github.com/repos/sharkdp/fd/releases/tags/v10.5.0, field "digest"), and matched the sha256sum of the
# downloaded archive on 2026-11-05.
# ripgrep is pinned to one build of Debian's package (13.0.0-4+b2) and comes from the live archive, which keeps only
# the current build of a package. When Debian replaces that build (a rebuild, a point release), apt-get no longer
# finds this version and 'traceworks images build pi' fails with "Version '13.0.0-4+b2' for 'ripgrep' was not found",
# without a change in this repository. That break is accepted: it is loud, it happens only when the image is built,
# and an image that is built already keeps working. To fix it, look up the build the archive has now
# (https://packages.debian.org/bookworm/ripgrep, or 'apt-cache policy ripgrep' in the base image after
# 'apt-get update'), put it into the apt-get install line below, and build the image again. A new upstream version
# (not 13.0.0) also changes what docs/workflow-format.md and SystemPromptLaunchContainerTest say about rg.
# curl downloads it, since curl honours HTTP_PROXY and HTTPS_PROXY as apt and npm do. curl and ca-certificates are
# removed in the same layer and never reach the image, and the sha256 checks the bytes they fetch, so their versions
# are not pinned.
RUN set -eu; \
    version=10.5.0; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in \
      amd64) target=x86_64-unknown-linux-gnu; sum=a1259cd129636efbc3fef123525c1b49e88fe5088c012630983c310e52fdfa95 ;; \
      arm64) target=aarch64-unknown-linux-gnu; sum=c0ee43802e3313a317c5af2f4eabd6ba13eeedd595af9775f05e18a13ac4f52c ;; \
      *) echo "The pi image has fd for amd64 and arm64 only, not for $arch" >&2; exit 1 ;; \
    esac; \
    asset="fd-v$version-$target.tar.gz"; \
    apt-get update; \
    apt-get install --no-install-recommends -y ripgrep=13.0.0-4+b2 curl ca-certificates; \
    curl -fsSL --proto '=https' --proto-redir '=https' -o "/tmp/$asset" \
      "https://github.com/sharkdp/fd/releases/download/v$version/$asset"; \
    echo "$sum  /tmp/$asset" | sha256sum -c -; \
    tar -xzf "/tmp/$asset" -C /tmp; \
    install -m 0755 "/tmp/fd-v$version-$target/fd" /usr/local/bin/fd; \
    rm -rf "/tmp/$asset" "/tmp/fd-v$version-$target"; \
    apt-get purge -y --auto-remove curl ca-certificates; \
    rm -rf /var/lib/apt/lists/*

# Pi 1.5.2 was checked offline, against a fake API on the container's loopback, with the arguments Traceworks gives
# it (PiCli, whose test keeps this line on PiCli.VERSION), in a read-only and in a writable workspace that held every
# project file Pi knows, under a home that trusts every project: --no-approve applies none of the workspace's .pi/
# (no settings, MCP servers, extensions, skills, prompts, themes or system prompt files), and --no-extensions and
# --no-mcp start no MCP server. Pi still parses .pi/settings.json for a session directory, which --no-session leaves
# unused (of a read-only workspace it cannot lock the file and says so on stderr), and renames a writable workspace's
# .pi/commands/ to .pi/prompts/.
# Upgrading Pi: check all of that again, offline, and raise PiCli.VERSION with the version below.
RUN npm install -g @earendil-works/pi-coding-agent@1.5.2 \
    && npm cache clean --force

# No USER line: Traceworks starts the container with --user, as the person who runs it.
