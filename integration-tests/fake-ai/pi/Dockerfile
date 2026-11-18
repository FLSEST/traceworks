# A fake Pi CLI for the integration tests: no model, no network. It stands in for the built-in image "pi" through
# ai_images in a test home's traceworks.json, so the tests never build, tag or remove traceworks/pi.
# The base is the one of images/pi, pinned by its digest. rg and fd are not needed: the fake runs no tool.
FROM docker.io/library/node:22-slim@sha256:e9f565d32859f0cbfb52125eafe8be39390f24dac302dc3cdf566e8ba3ab21ee
LABEL org.opencontainers.image.description="a fake Pi CLI for the integration tests: a script that answers from a file"

COPY pi /usr/local/bin/pi
RUN chmod 0755 /usr/local/bin/pi

# No USER line: Traceworks starts the container with --user, as the person who runs it.
