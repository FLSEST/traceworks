# The image of the build self-audit's steps: the facts tool (src/), compiled here, on the JDK the tool runs on and
# with the Maven its maven step runs. Maven comes out of its official image (3.10.0); the JDK is SapMachine 21 on
# Ubuntu 24.04, the base of this image. Both are pinned by digest, so the same Dockerfile always starts from the same
# bytes. Nothing else is installed or downloaded: the tool is plain JDK.
# Only /usr/share/maven is taken from this stage. Its tag names Maven's version alone, so that Dependabot reads a
# newer Maven as what it is; the JDK that this stage holds is never used.
FROM docker.io/library/maven:3.10.0@sha256:a83dca2f65c72ace081f55a63231adbd6d88741caa4fdc68f7d5c9e450d1070c AS maven
FROM docker.io/library/sapmachine:21-jdk-ubuntu-noble@sha256:701a9f10d16130748b91ec48cbfbf6a48fbe2d0e1de510cb38af8f9e1435856c
LABEL org.opencontainers.image.description="the build facts tool compiled from src/ on Maven 3.10.0 and JDK 21: measures the build, dependencies and registries"

COPY --from=maven /usr/share/maven /usr/share/maven
ENV MAVEN_HOME=/usr/share/maven
RUN ln -s /usr/share/maven/bin/mvn /usr/bin/mvn

COPY src /opt/facts/src
RUN find /opt/facts/src -name '*.java' | sort > /tmp/sources \
    && javac --release 21 -d /opt/facts/classes @/tmp/sources \
    && rm /tmp/sources \
    && printf '%s\n' '#!/bin/sh' \
        'exec java -XX:-UsePerfData -cp /opt/facts/classes traceworks.selfaudit.build.Main "$@"' \
        > /usr/local/bin/facts \
    && chmod 755 /usr/local/bin/facts

# A step runs bash or the tool directly, so no entrypoint of a base image stands in front of it.
ENTRYPOINT []

# No USER line: Traceworks starts the container with --user, as the person who runs it.
