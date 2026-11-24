# The image of the egress proxy: the only door of an AI step's container. Traceworks starts it next to the step, and
# the step reaches nothing but the targets the proxy was started with (src/, compiled here; it uses the JDK only).
# The base is pinned by digest, so the same Dockerfile always starts from the same bytes.
FROM docker.io/library/sapmachine:21-jdk-ubuntu-noble@sha256:8e5324772ff5a5a95ca4c1f056a89e70894af9abc97c88dc5b23bf8cfb3aa070
LABEL org.opencontainers.image.description="the allowlist proxy of an AI step, compiled from src/ on JDK 21: tunnels CONNECT to the listed hosts only (plain HTTP only to the operator's own model servers) and prints one line per connection"

COPY src /opt/egress/src
RUN find /opt/egress/src -name '*.java' | sort > /tmp/sources \
    && javac --release 21 -d /opt/egress/classes @/tmp/sources \
    && rm /tmp/sources

# The arguments of the container are the allowed targets. -Xshare:off and -XX:-UsePerfData: nothing is written at start.
ENTRYPOINT ["java", "-XX:-UsePerfData", "-Xshare:off", "-Xss256k", "-cp", "/opt/egress/classes", "traceworks.egress.Egress"]

# No USER line: Traceworks starts the container with --user, as the person who runs it.
