# A package image for the combination tests: the pinned base of the built-in shell image, plus one entrypoint that
# notes each start in the scratch directory. The step that names it is refused before its container starts, so the
# note must never appear.
FROM docker.io/library/debian:bookworm-slim@sha256:e67eeead736ff7bbe258ef088bff683635c561b1fcfefad0806c1df4ab49ce0e
LABEL org.opencontainers.image.description="a tool that notes that it ran, for a step that must be refused before it starts"

COPY entrypoint.sh /opt/tool/entrypoint.sh
RUN chmod 755 /opt/tool/entrypoint.sh
ENTRYPOINT ["/opt/tool/entrypoint.sh"]
