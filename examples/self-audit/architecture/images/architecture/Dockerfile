# The image of the architecture self-audit's deterministic steps: the architecture tool (src/), compiled here, on a
# full JDK. It needs the JDK, not a runtime: it parses and attributes the audited sources with the JDK's compiler,
# against the platform classes of every module they use. The base is pinned by digest, so the same Dockerfile always
# starts from the same bytes.
FROM eclipse-temurin:21-jdk-noble@sha256:b468c3fc688b14450571494f588bd939378e7fd542ed5a73f8efc13f17872a87
LABEL org.opencontainers.image.description="the architecture tool (facts and report) compiled from src/ on JDK 21, which parses the audited sources with javac"

COPY src /opt/architecture/src
RUN find /opt/architecture/src -name '*.java' | sort > /tmp/sources \
    && javac --release 21 -d /opt/architecture/classes @/tmp/sources \
    && rm /tmp/sources \
    && printf '%s\n' '#!/bin/sh' \
        'exec java -XX:-UsePerfData -cp /opt/architecture/classes traceworks.selfaudit.architecture.Main "$@"' \
        > /usr/local/bin/architecture \
    && chmod 755 /usr/local/bin/architecture

# No USER line: Traceworks starts the container with --user, as the person who runs it.
