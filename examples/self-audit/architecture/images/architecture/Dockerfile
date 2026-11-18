# The image of the architecture self-audit's deterministic steps: the architecture tool (src/), compiled here, on a
# full JDK. It needs the JDK, not a runtime: it parses and attributes the audited sources with the JDK's compiler,
# against the platform classes of every module they use. The base is pinned by digest, so the same Dockerfile always
# starts from the same bytes.
FROM sapmachine:21-jdk-ubuntu-noble@sha256:701a9f10d16130748b91ec48cbfbf6a48fbe2d0e1de510cb38af8f9e1435856c
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
