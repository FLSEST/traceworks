# A package image for the integration tests: the pinned base of the built-in shell image, plus one file a step
# reads to show that it ran in this image.
FROM debian:bookworm-slim@sha256:99c0b25c46d0214d5a2091092e7908907dcaecd6a962cde0ac700acee222b6f3
LABEL org.opencontainers.image.description="a probe for the package-image tests: a shell script that prints what it sees"

COPY marker.txt /opt/it/marker.txt
