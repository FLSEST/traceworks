# A package image for the combination tests: the pinned base of the built-in shell image, plus one entrypoint that
# notes each start in the scratch directory. The step that names it is refused before its container starts, so the
# note must never appear.
FROM debian:bookworm-slim@sha256:734a72b3d6142a71993b4bc580ca01d8ca687f77a2c422d96a961d3376b179bc
LABEL org.opencontainers.image.description="a tool that notes that it ran, for a step that must be refused before it starts"

COPY entrypoint.sh /opt/tool/entrypoint.sh
RUN chmod 755 /opt/tool/entrypoint.sh
ENTRYPOINT ["/opt/tool/entrypoint.sh"]
