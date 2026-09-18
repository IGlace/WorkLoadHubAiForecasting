#!/usr/bin/env bash
# The local PostgreSQL: one container under the same engine as the development box, holding the
# database the experiment driver and the sample host connect to by default. The host application's
# database is PostgreSQL, so this is the one engine the module ever runs on.
#
# Usage: bash scripts/postgres.sh up              create the volume and start the container, or start it again
#        bash scripts/postgres.sh stop            stop it; `up` brings it back with its data
#        bash scripts/postgres.sh rm [--volume]   remove the container; --volume also removes the data
#        bash scripts/postgres.sh status          is it running, which port, does the database answer
#        bash scripts/postgres.sh psql [args...]  open psql inside the container; extra arguments are psql's
#                                                 own (-c, -f, ...), and a piped stdin is read as a script
#        bash scripts/postgres.sh --help          this header
#
# The database is `workloadhub`, user `workloadhub`, password `workloadhub`, port 5432, reachable as
# jdbc:postgresql://localhost:5432/workloadhub from Windows and from inside the development box
# alike, since the box runs on the host network. The schema task_service inside it is created by the
# driver (`bash server/tools/experiment.sh init-db`), not here: this script knows nothing about
# WorkloadHub.
#
# CONTAINER_ENGINE  podman or docker; the default is whichever is on PATH, podman first.
# WHF_PG_IMAGE      the image (default postgres:18-alpine; the schema dump came from an 18 server).
# WHF_PG_CONTAINER  the container's name (default whf-postgres).
# WHF_PG_VOLUME     the named volume for the data (default whf-pg).
# WHF_PG_PORT       the host port (default 5432).
set -euo pipefail

help() {
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
}

case "${1:-}" in
help | -h | --help)
    help
    exit 0
    ;;
"")
    help
    exit 2
    ;;
up | stop | rm | status | psql) ;;
*)
    help
    echo "unknown command: $1" >&2
    exit 2
    ;;
esac

engine="${CONTAINER_ENGINE:-}"
if [ -z "$engine" ]; then
    if command -v podman >/dev/null 2>&1; then
        engine=podman
    elif command -v docker >/dev/null 2>&1; then
        engine=docker
    else
        echo "no container engine on PATH: install podman or docker" >&2
        exit 1
    fi
fi
command -v "$engine" >/dev/null 2>&1 || {
    echo "CONTAINER_ENGINE=$engine is not on PATH" >&2
    exit 1
}

image="${WHF_PG_IMAGE:-postgres:18-alpine}"
name="${WHF_PG_CONTAINER:-whf-postgres}"
volume="${WHF_PG_VOLUME:-whf-pg}"
port="${WHF_PG_PORT:-5432}"

exists() { "$engine" inspect --type container "$name" >/dev/null 2>&1; }
running() { [ "$("$engine" inspect -f '{{.State.Running}}' "$name" 2>/dev/null || true)" = true ]; }

case "$1" in
up)
    if exists; then
        if running; then
            echo "$name is already running on port $port"
        else
            "$engine" start "$name" >/dev/null
            echo "$name started again on port $port"
        fi
        exit 0
    fi
    echo "==> creating $name from $image"
    "$engine" volume create "$volume" >/dev/null 2>&1 || true
    # Mounted at /var/lib/postgresql, not .../data: the postgres:18+ images keep the data directory
    # under a major-version-specific subdirectory of /var/lib/postgresql and, on a bind mount at
    # .../data, treat that mount itself as leftover data from before an upgrade and refuse to start
    # (docker-entrypoint.sh's own OLD_DATABASES check). Mounting the parent is the images'
    # documented fix and needs nothing else changed.
    "$engine" run -d --name "$name" --restart unless-stopped \
        -p "$port:5432" \
        -e POSTGRES_DB=workloadhub -e POSTGRES_USER=workloadhub -e POSTGRES_PASSWORD=workloadhub \
        -v "$volume:/var/lib/postgresql" \
        "$image" >/dev/null
    echo "waiting for the database to answer"
    for _ in $(seq 1 30); do
        if "$engine" exec "$name" pg_isready -U workloadhub -d workloadhub >/dev/null 2>&1; then
            echo "$name is up: jdbc:postgresql://localhost:$port/workloadhub (user workloadhub, password workloadhub)"
            echo "next: bash server/tools/experiment.sh init-db"
            exit 0
        fi
        sleep 1
    done
    echo "$name did not answer within 30 s; inspect it with: $engine logs $name" >&2
    exit 1
    ;;
stop)
    if running; then
        "$engine" stop "$name" >/dev/null
        echo "$name stopped; \`up\` brings it back with its data"
    else
        echo "$name is not running"
    fi
    ;;
rm)
    if exists; then
        "$engine" rm -f "$name" >/dev/null
        echo "$name removed"
    else
        echo "$name does not exist"
    fi
    if [ "${2:-}" = "--volume" ]; then
        "$engine" volume rm "$volume" >/dev/null 2>&1 && echo "volume $volume removed" || echo "volume $volume did not exist"
    else
        echo "the data stays in volume $volume; pass --volume to remove it too"
    fi
    ;;
status)
    if ! exists; then
        echo "$name does not exist; run: bash scripts/postgres.sh up"
        exit 1
    fi
    if running; then
        echo "$name is running on port $port ($image), volume $volume"
        if "$engine" exec "$name" pg_isready -U workloadhub -d workloadhub >/dev/null 2>&1; then
            echo "the database answers: jdbc:postgresql://localhost:$port/workloadhub"
        else
            echo "the container runs but the database does not answer yet"
        fi
    else
        echo "$name exists but is stopped; run: bash scripts/postgres.sh up"
        exit 1
    fi
    ;;
psql)
    running || {
        echo "$name is not running; run: bash scripts/postgres.sh up" >&2
        exit 1
    }
    # -t only when there is a terminal to pass on, as devbox.sh exec does: forcing it into a pipe fills the
    # output with escape codes, and `postgres.sh psql < query.sql` must read the file, not end of file.
    if [ -t 0 ] && [ -t 1 ]; then
        exec "$engine" exec -it "$name" psql -U workloadhub -d workloadhub "${@:2}"
    fi
    exec "$engine" exec -i "$name" psql -U workloadhub -d workloadhub "${@:2}"
    ;;
esac
