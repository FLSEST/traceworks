# A package image for the integration tests: the pinned base of the built-in shell image, plus one file a step
# reads to show that it ran in this image.
FROM debian:bookworm-slim@sha256:734a72b3d6142a71993b4bc580ca01d8ca687f77a2c422d96a961d3376b179bc
LABEL org.opencontainers.image.description="a probe for the package-image tests: a shell script that prints what it sees"

COPY marker.txt /opt/it/marker.txt
