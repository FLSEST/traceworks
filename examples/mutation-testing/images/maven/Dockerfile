# The image of the Maven steps of mutation testing: Maven on JDK 21, and ssh-keygen, which tests of the audited project
# start. A test whose program the image lacks is red without any change and would not count. The base is pinned by
# digest, so the same Dockerfile always starts from the same bytes.
FROM maven:3.10.0-eclipse-temurin-21-noble@sha256:f6d6c7eb62ca6a7034c17bb04a6177a8269ca38e3a1febf95094dfcd97d1305b
LABEL org.opencontainers.image.description="Maven 3.10.0 on JDK 21 with git and ssh-keygen: builds the audited project and runs PIT on it"

RUN apt-get update && apt-get install -y --no-install-recommends openssh-client && rm -rf /var/lib/apt/lists/*

# No USER line: Traceworks starts the container with --user, as the person who runs it.
