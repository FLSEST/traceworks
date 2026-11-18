# The image of the Maven steps of mutation testing: Maven on JDK 21, and ssh-keygen, which tests of the audited project
# start. A test whose program the image lacks is red without any change and would not count. The base is pinned by
# digest, so the same Dockerfile always starts from the same bytes.
FROM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320
LABEL org.opencontainers.image.description="Maven 3.9.16 on JDK 21 with git and ssh-keygen: builds the audited project and runs PIT on it"

RUN apt-get update && apt-get install -y --no-install-recommends openssh-client && rm -rf /var/lib/apt/lists/*

# No USER line: Traceworks starts the container with --user, as the person who runs it.
