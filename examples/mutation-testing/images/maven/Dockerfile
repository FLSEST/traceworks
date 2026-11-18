# The image of the Maven steps of mutation testing: Maven on JDK 21, and git and ssh-keygen, which tests of the audited
# project start. A test whose program the image lacks is red without any change and would not count. Maven comes out
# of its official image; the JDK is SapMachine's, the base of this image. Both are pinned by digest, so the same
# Dockerfile always starts from the same bytes.
FROM maven:3.10.0-eclipse-temurin-21-noble@sha256:f6d6c7eb62ca6a7034c17bb04a6177a8269ca38e3a1febf95094dfcd97d1305b AS maven
FROM sapmachine:21-jdk-ubuntu-noble@sha256:701a9f10d16130748b91ec48cbfbf6a48fbe2d0e1de510cb38af8f9e1435856c
LABEL org.opencontainers.image.description="Maven 3.10.0 on JDK 21 with git and ssh-keygen: builds the audited project and runs PIT on it"

COPY --from=maven /usr/share/maven /usr/share/maven
ENV MAVEN_HOME=/usr/share/maven
RUN ln -s /usr/share/maven/bin/mvn /usr/bin/mvn \
    && apt-get update && apt-get install -y --no-install-recommends git openssh-client && rm -rf /var/lib/apt/lists/*

# No USER line: Traceworks starts the container with --user, as the person who runs it.
