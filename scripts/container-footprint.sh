#!/bin/sh
# Counts what a command made the container runtime create: containers, networks and network connections.
#
#   scripts/container-footprint.sh mvn -o -Pit verify
#   scripts/container-footprint.sh mvn -o -Pit verify -pl integration-tests -Dtest='Ai*IT'
#   TRACEWORKS_FOOTPRINT_EVENTS=/tmp/events.tsv scripts/container-footprint.sh mvn -o -Pcontainers verify
#
# Why: on a Linux host every network is a bridge device and every connection of a container to a network is a pair
# of veth devices, one end on the host, and the machine's network services react to each. The numbers make two runs comparable: run the
# same command before and after a change and compare the lines.
#
# How: it follows the runtime's events while the command runs (the daemon keeps only its last few hundred events, so
# asking afterwards would miss most of a long run), and lists containers and networks before and after to name what
# was left behind. The events are those of the whole daemon: anything else that starts containers meanwhile is
# counted too, so run it alone. The lines "of Traceworks" count the names that start with "traceworks-", which the
# containers and networks of a run and of the container tests carry.
#
# The runtime is TRACEWORKS_IT_RUNTIME (docker, the default, or podman), as for the integration tests. Events are
# followed for docker only; with podman the script prints the before and after listing and says so.
#
# Exit code: that of the command. Verified with a stand-in for the docker client only.
set -eu

if [ $# -eq 0 ]; then
    echo "usage: scripts/container-footprint.sh <command> [arguments]" >&2
    exit 2
fi

runtime=${TRACEWORKS_IT_RUNTIME:-docker}
case "$runtime" in
    docker|podman) ;;
    *) echo "container-footprint: TRACEWORKS_IT_RUNTIME is '$runtime'; it must be docker or podman" >&2; exit 2 ;;
esac
if ! "$runtime" version >/dev/null 2>&1; then
    echo "container-footprint: '$runtime version' does not work, so nothing can be counted" >&2
    exit 2
fi

work=$(mktemp -d)
events_pid=
cleanup() {
    if [ -n "$events_pid" ]; then
        kill "$events_pid" 2>/dev/null || true
    fi
    rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

"$runtime" ps -a --format '{{.Names}}' | sort > "$work/containers.before"
"$runtime" network ls --format '{{.Name}}' | sort > "$work/networks.before"

: > "$work/events"
if [ "$runtime" = docker ]; then
    # One line per event: type, action, name, and for a network its driver. From a second ago, so that what is
    # created while the client attaches is in the stream too.
    since=$(( $(date +%s) - 1 ))
    docker events --since "$since" --filter type=container --filter type=network \
        --format '{{.Type}}	{{.Action}}	{{.Actor.Attributes.name}}	{{.Actor.Attributes.type}}' \
        > "$work/events" 2> "$work/events.err" &
    events_pid=$!
fi

started=$(date +%s)
code=0
"$@" || code=$?
ended=$(date +%s)

if [ -n "$events_pid" ]; then
    sleep 2   # the events of the last container may take a moment
    kill "$events_pid" 2>/dev/null || true
    wait "$events_pid" 2>/dev/null || true
    events_pid=
fi

"$runtime" ps -a --format '{{.Names}}' | sort > "$work/containers.after"
"$runtime" network ls --format '{{.Name}}' | sort > "$work/networks.after"

# count <type> <action> [name prefix]: the events of that type and action; "none" and "host" are no devices
count() {
    awk -F '\t' -v type="$1" -v action="$2" -v prefix="${3:-}" '
        $1 == type && $2 == action && $3 != "none" && $3 != "host" && index($3, prefix) == 1 { n++ }
        END { print n + 0 }' "$work/events"
}

echo
echo "container-footprint: $* (exit $code, $(( ended - started )) s, $runtime)"
if [ "$runtime" = docker ]; then
    containers=$(count container create)
    networks=$(count network create)
    connections=$(count network connect)
    echo "  containers created:            $containers  (of Traceworks: $(count container create traceworks-))"
    echo "  networks created:              $networks  (of Traceworks: $(count network create traceworks-); each is a bridge device)"
    echo "  network connections:           $connections  (to a network of Traceworks: $(count network connect traceworks-); each is a veth pair)"
    echo "  network devices on the host:   $(( networks + connections ))  (a bridge per network, the host's end of a veth pair per connection)"
    echo "  networks removed:              $(count network destroy)"
    if [ -s "$work/events.err" ]; then
        echo "  the event stream reported: $(tr '\n' ' ' < "$work/events.err")"
    fi
    if [ -n "${TRACEWORKS_FOOTPRINT_EVENTS:-}" ]; then
        cp "$work/events" "$TRACEWORKS_FOOTPRINT_EVENTS"
        echo "  the events are in:             $TRACEWORKS_FOOTPRINT_EVENTS"
    fi
else
    echo "  events are followed for docker only: with podman only what was left behind is known"
fi
left_containers=$(comm -13 "$work/containers.before" "$work/containers.after" | tr '\n' ' ')
left_networks=$(comm -13 "$work/networks.before" "$work/networks.after" | tr '\n' ' ')
echo "  containers left behind:        ${left_containers:-none}"
echo "  networks left behind:          ${left_networks:-none}"
exit "$code"
