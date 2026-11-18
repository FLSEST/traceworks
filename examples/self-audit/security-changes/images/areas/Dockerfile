# The image of the security self-audit's deterministic steps: the area finder (src/), compiled here, on a full JDK.
# It needs the JDK, not a runtime: it parses and attributes the audited sources with the JDK's compiler, against the
# platform classes of every module they use. The base is pinned by digest, so the same Dockerfile always starts from
# the same bytes.
FROM docker.io/library/sapmachine:21-jdk-ubuntu-noble@sha256:701a9f10d16130748b91ec48cbfbf6a48fbe2d0e1de510cb38af8f9e1435856c
LABEL org.opencontainers.image.description="the security area finder and report tool compiled from src/ on JDK 21, which parses the audited sources with javac"

COPY src /opt/areas/src
RUN find /opt/areas/src -name '*.java' | sort > /tmp/sources \
    && javac --release 21 -d /opt/areas/classes @/tmp/sources \
    && rm /tmp/sources \
    && printf '%s\n' '#!/bin/sh' \
        'exec java -XX:-UsePerfData -cp /opt/areas/classes traceworks.selfaudit.areas.Main "$@"' \
        > /usr/local/bin/areas \
    && chmod 755 /usr/local/bin/areas

# No USER line: Traceworks starts the container with --user, as the person who runs it.
