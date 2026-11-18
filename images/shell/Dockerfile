# The image of a shell step without a container_image: the tools a plain script needs, and nothing else.
# The base is pinned by digest, so the same Dockerfile always starts from the same bytes.
FROM docker.io/library/debian:bookworm-slim@sha256:734a72b3d6142a71993b4bc580ca01d8ca687f77a2c422d96a961d3376b179bc
LABEL org.opencontainers.image.description="bash, coreutils, git, python3, jq and ca-certificates on Debian bookworm: the tools of a plain shell step"

RUN apt-get update \
    && apt-get install --no-install-recommends -y bash coreutils git python3 jq ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# No USER line: Traceworks starts the container with --user, as the person who runs it.
