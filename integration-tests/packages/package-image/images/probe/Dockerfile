# A package image for the integration tests: the pinned base of the built-in shell image, plus one file a step
# reads to show that it ran in this image.
FROM docker.io/library/debian:bookworm-slim@sha256:e67eeead736ff7bbe258ef088bff683635c561b1fcfefad0806c1df4ab49ce0e
LABEL org.opencontainers.image.description="a probe for the package-image tests: a shell script that prints what it sees"

COPY marker.txt /opt/it/marker.txt
