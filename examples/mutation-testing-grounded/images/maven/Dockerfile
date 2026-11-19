# The image of the Maven steps of mutation testing: Maven on JDK 21, and git and ssh-keygen, which tests of the audited
# project start. A test whose program the image lacks is red without any change and would not count. Maven comes out
# of its official image; the JDK is SapMachine's, the base of this image. Both are pinned by digest, so the same
# Dockerfile always starts from the same bytes.
# Only /usr/share/maven is taken from this stage. Its tag names Maven's version alone, so that Dependabot reads a
# newer Maven as what it is; the JDK that this stage holds is never used.
FROM docker.io/library/maven:3.10.0@sha256:3175c44b207d6698aecb9bbb5bf285b5b4efcf7f567d6f65d88cd218276a6ab2 AS maven
FROM docker.io/library/sapmachine:21-jdk-ubuntu-noble@sha256:701a9f10d16130748b91ec48cbfbf6a48fbe2d0e1de510cb38af8f9e1435856c
LABEL org.opencontainers.image.description="Maven 3.10.0 on JDK 21 with git and ssh-keygen: builds the audited project and runs PIT on it"

COPY --from=maven /usr/share/maven /usr/share/maven
ENV MAVEN_HOME=/usr/share/maven
RUN ln -s /usr/share/maven/bin/mvn /usr/bin/mvn \
    && apt-get update && apt-get install -y --no-install-recommends git openssh-client && rm -rf /var/lib/apt/lists/*

# No USER line: Traceworks starts the container with --user, as the person who runs it.
