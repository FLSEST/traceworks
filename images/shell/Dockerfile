# The image of a shell step without a container_image: the tools a plain script needs, and nothing else.
# The base is pinned by digest, so the same Dockerfile always starts from the same bytes.
FROM docker.io/library/debian:bookworm-slim@sha256:e67eeead736ff7bbe258ef088bff683635c561b1fcfefad0806c1df4ab49ce0e
LABEL org.opencontainers.image.description="bash, coreutils, git, python3, jq and ca-certificates on Debian bookworm: the tools of a plain shell step"

RUN apt-get update \
    && apt-get install --no-install-recommends -y bash coreutils git python3 jq ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# No USER line: Traceworks starts the container with --user, as the person who runs it.
