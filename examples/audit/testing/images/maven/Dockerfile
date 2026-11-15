# The image of the testing audit's Maven steps: Maven on JDK 21, and ssh-keygen, which tests of the audited project
# start. A test whose program the image lacks is red without any change and would not count. The base is pinned by
# digest, so the same Dockerfile always starts from the same bytes.
FROM maven:3.9.8-eclipse-temurin-21@sha256:de18e4e51396ad9266f76669779ed6d6b49fbb0f45ed6f39169d87378d548d7d
LABEL org.opencontainers.image.description="Maven 3.9.8 on JDK 21 with git and ssh-keygen: builds the audited project and runs PIT on it"

RUN apt-get update && apt-get install -y --no-install-recommends openssh-client && rm -rf /var/lib/apt/lists/*

# No USER line: Traceworks starts the container with --user, as the person who runs it.
