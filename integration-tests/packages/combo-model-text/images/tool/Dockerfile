# A package image for the combination tests: the pinned base of the built-in shell image, plus one entrypoint that
# notes each start in the scratch directory. The step that names it is refused before its container starts, so the
# note must never appear.
FROM debian:bookworm-slim@sha256:99c0b25c46d0214d5a2091092e7908907dcaecd6a962cde0ac700acee222b6f3
LABEL org.opencontainers.image.description="a tool that notes that it ran, for a step that must be refused before it starts"

COPY entrypoint.sh /opt/tool/entrypoint.sh
RUN chmod 755 /opt/tool/entrypoint.sh
ENTRYPOINT ["/opt/tool/entrypoint.sh"]
