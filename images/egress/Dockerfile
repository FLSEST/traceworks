# The image of the egress proxy: the only door of an AI step's container. Traceworks starts it next to the step, and
# the step reaches nothing but the targets the proxy was started with (src/, compiled here; it uses the JDK only).
# The base is pinned by digest, so the same Dockerfile always starts from the same bytes.
FROM eclipse-temurin:21-jdk-noble@sha256:b468c3fc688b14450571494f588bd939378e7fd542ed5a73f8efc13f17872a87
LABEL org.opencontainers.image.description="the allowlist proxy of an AI step, compiled from src/ on JDK 21: tunnels CONNECT to the listed hosts only and prints one line per connection"

COPY src /opt/egress/src
RUN find /opt/egress/src -name '*.java' | sort > /tmp/sources \
    && javac --release 21 -d /opt/egress/classes @/tmp/sources \
    && rm /tmp/sources

# The arguments of the container are the allowed targets. -Xshare:off and -XX:-UsePerfData: nothing is written at start.
ENTRYPOINT ["java", "-XX:-UsePerfData", "-Xshare:off", "-Xss256k", "-cp", "/opt/egress/classes", "traceworks.egress.Egress"]

# No USER line: Traceworks starts the container with --user, as the person who runs it.
