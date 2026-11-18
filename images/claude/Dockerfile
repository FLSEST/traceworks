# The image of the Claude AI provider: its command line tool on a pinned Node.js base.
# Pinned are the base image, by its digest, and the CLI, by its version. npm resolves the CLI's own dependencies
# when the image is built, so a build on another day can differ in them.
FROM node:22-slim@sha256:e9f565d32859f0cbfb52125eafe8be39390f24dac302dc3cdf566e8ba3ab21ee
LABEL org.opencontainers.image.description="the Claude Code CLI 2.1.327 on Node.js 22: runs an AI step of the CLAUDE provider"

RUN npm install -g @anthropic-ai/claude-code@2.1.327 \
    && npm cache clean --force

# No USER line: Traceworks starts the container with --user, as the person who runs it.
