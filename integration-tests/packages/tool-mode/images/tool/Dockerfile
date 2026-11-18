# A package image for the tool-mode integration tests: the pinned base of the built-in shell image, plus one
# entrypoint script. A step in tool mode runs this entrypoint with its container_args, with no shell in between.
FROM docker.io/library/debian:bookworm-slim@sha256:734a72b3d6142a71993b4bc580ca01d8ca687f77a2c422d96a961d3376b179bc
LABEL org.opencontainers.image.description="a fake scanner for the tool-mode tests: a shell script that writes SARIF"

COPY entrypoint.sh /opt/tool/entrypoint.sh
RUN chmod 755 /opt/tool/entrypoint.sh
ENTRYPOINT ["/opt/tool/entrypoint.sh"]
