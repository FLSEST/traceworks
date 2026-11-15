# A fake Claude CLI for the integration tests: no model, no network. It stands in for the built-in image "claude"
# through ai_images in a test home's traceworks.json, so the tests never build, tag or remove traceworks/claude.
# The base is the one of images/claude, pinned by its digest.
FROM node:22-slim@sha256:4b150b48b4116e2a22f2c39cc27025fbf4f153e2078fd3b14d159fdb5c7279da
LABEL org.opencontainers.image.description="a fake Claude CLI for the integration tests: a script that answers from a file"

COPY claude /usr/local/bin/claude
RUN chmod 0755 /usr/local/bin/claude

# No USER line: Traceworks starts the container with --user, as the person who runs it.
