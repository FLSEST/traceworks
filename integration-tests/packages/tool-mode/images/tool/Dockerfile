# A package image for the tool-mode integration tests: the pinned base of the built-in shell image, plus one
# entrypoint script. A step in tool mode runs this entrypoint with its container_args, with no shell in between.
FROM debian:bookworm-slim@sha256:99c0b25c46d0214d5a2091092e7908907dcaecd6a962cde0ac700acee222b6f3
LABEL org.opencontainers.image.description="a fake scanner for the tool-mode tests: a shell script that writes SARIF"

COPY entrypoint.sh /opt/tool/entrypoint.sh
RUN chmod 755 /opt/tool/entrypoint.sh
ENTRYPOINT ["/opt/tool/entrypoint.sh"]
