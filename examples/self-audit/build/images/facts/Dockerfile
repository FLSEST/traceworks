# The image of the build self-audit's steps: the facts tool (src/), compiled here, on Maven's official image, which
# brings the JDK the tool runs on and the Maven its maven step runs. The base is pinned by digest, so the same Dockerfile
# always starts from the same bytes; the tag names Maven 3.9.16 on Eclipse Temurin 21 (Ubuntu 24.04). Nothing else is
# installed or downloaded: the tool is plain JDK.
FROM maven:3.9.16-eclipse-temurin-21-noble@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320
LABEL org.opencontainers.image.description="the build facts tool compiled from src/ on Maven 3.9.16 and JDK 21: measures the build, dependencies and registries"

COPY src /opt/facts/src
RUN find /opt/facts/src -name '*.java' | sort > /tmp/sources \
    && javac --release 21 -d /opt/facts/classes @/tmp/sources \
    && rm /tmp/sources \
    && printf '%s\n' '#!/bin/sh' \
        'exec java -XX:-UsePerfData -cp /opt/facts/classes traceworks.selfaudit.build.Main "$@"' \
        > /usr/local/bin/facts \
    && chmod 755 /usr/local/bin/facts

# The official image's entrypoint copies /usr/share/maven/ref into the user's ~/.m2; a step runs bash or the tool
# directly, so it is reset here.
ENTRYPOINT []

# No USER line: Traceworks starts the container with --user, as the person who runs it.
