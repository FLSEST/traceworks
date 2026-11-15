# The image of the Claude AI provider: its command line tool on a pinned Node.js base.
# Pinned are the base image, by its digest, and the CLI, by its version. npm resolves the CLI's own dependencies
# when the image is built, so a build on another day can differ in them.
FROM node:22-slim@sha256:4b150b48b4116e2a22f2c39cc27025fbf4f153e2078fd3b14d159fdb5c7279da
LABEL org.opencontainers.image.description="the Claude Code CLI 2.1.290 on Node.js 22: runs an AI step of the CLAUDE provider"

RUN npm install -g @anthropic-ai/claude-code@2.1.290 \
    && npm cache clean --force

# No USER line: Traceworks starts the container with --user, as the person who runs it.
