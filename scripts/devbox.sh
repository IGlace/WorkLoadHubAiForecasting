#!/usr/bin/env bash
# The development box: a long-running Ubuntu container with Java 21, Maven, uv and this repository
# bind-mounted, so work that needs Linux can be done by hand inside it. It replaces installing a JDK
# into a WSL distro, which on this machine cannot be done at all: that distro has no working DNS.
#
# The repository is mounted, not copied. An edit inside the container is an edit on Windows and the
# other way round, so an editor on Windows and a shell in here work on the same files.
#
# Usage: bash scripts/devbox.sh up [engine args...]   build the image if needed, start the box
#        bash scripts/devbox.sh shell                 open a shell inside it (starting it if needed)
#        bash scripts/devbox.sh exec <cmd> [args...]  run one command inside it
#        bash scripts/devbox.sh status                what it is, what is mounted, is the socket live
#        bash scripts/devbox.sh stop                  stop it; `up` brings it back with state intact
#        bash scripts/devbox.sh restart               stop and start it again
#        bash scripts/devbox.sh rm                    remove it, keeping the cache volumes
#        bash scripts/devbox.sh rebuild               rebuild the image and recreate the box
#        bash scripts/devbox.sh --help                this header
#
# It keeps running until stopped, and comes back by itself if it crashes. It does not survive a
# restart of the engine: podman's machine does not enable podman-restart.service, so after a reboot
# of Windows, start the machine and run `up` again.
#
# The mounts, the timezone and the socket are settled when the box is created. Changing any of the
# variables below does nothing to a box that already exists: `rm` then `up`, or `rebuild`.
#
# CONTAINER_ENGINE  podman or docker; the default is whichever is on PATH, podman first.
# WHF_IMAGE         the image to build and run (default whf-dev:21).
# WHF_CONTAINER     the container's name (default whf-dev).
# WHF_DATA          host directory mounted at /data, for seeds and exports, which must stay out of
#                   the repository (default ~/whf; created if missing). The database itself lives in
#                   the whf-pg volume of scripts/postgres.sh.
# WHF_PG_CONTAINER  the local PostgreSQL's container name, as scripts/postgres.sh reads it (default
#                   whf-postgres); only `status` looks at it here.
# WHF_M2_VOLUME     named volume for ~/.m2 (default whf-m2). Losing it costs a full re-resolve:
#                   measured once at 9:45 for `mvn verify` against 5:47 with it.
# WHF_CACHE_VOLUME  named volume for ~/.cache (default whf-cache), uv's download cache.
# WHF_UV_VOLUME     named volume for ~/.local/share/uv (default whf-uv), where uv keeps the Python
#                   versions it downloads: 93 MB, and a fresh one costs a 29 MB download.
# WHF_COPILOT_VOLUME  named volume for ~/.copilot (default whf-copilot). The SDK unpacks its
#                   runtime there, to a path it fixes itself, and that costs about 91 MB each time.
# WHF_GH_VOLUME     named volume for ~/.config/gh (default whf-gh), where `gh auth login` keeps the
#                   token it obtained. Without it every rebuild costs another browser sign-in.
# WHF_TZ            the container's timezone (default UTC). It decides what "today" means to a
#                   forecast run, so set it before creating the box if you run without --as-of.
# CONTAINER_SOCK    the engine socket the box mounts so Testcontainers can start PostgreSQL as a
#                   sibling container. The gate needs it: without the socket every database test
#                   fails. The default is what podman reports for itself, or the usual docker path.
#                   Set it to the empty string to leave the socket out.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The header is the help: printing it beats keeping a second copy of it in step. A blank line that
# is not a comment ends it, so keep the header unbroken.
help() {
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
}

# Answer before anything is set up: reading the help must work on a machine with no engine, and
# must not create WHF_DATA as a side effect. Naming the commands twice is the price.
case "${1:-}" in
help | -h | --help)
    help
    exit 0
    ;;
"")
    help
    exit 2
    ;;
up | shell | exec | status | stop | restart | rm | rebuild) ;;
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
    echo "CONTAINER_ENGINE '$engine' is not on PATH" >&2
    exit 1
}
# CONTAINER_ENGINE may be a path or spelled with capitals, so decide on the program's name:
# comparing the whole string would pick docker's socket default for /usr/bin/podman.
case "$(basename "$engine" | tr '[:upper:]' '[:lower:]')" in
podman*) podman=1 ;;
*) podman=0 ;;
esac

image="${WHF_IMAGE:-whf-dev:21}"
name="${WHF_CONTAINER:-whf-dev}"
m2_volume="${WHF_M2_VOLUME:-whf-m2}"
cache_volume="${WHF_CACHE_VOLUME:-whf-cache}"
uv_volume="${WHF_UV_VOLUME:-whf-uv}"
copilot_volume="${WHF_COPILOT_VOLUME:-whf-copilot}"
gh_volume="${WHF_GH_VOLUME:-whf-gh}"
data="${WHF_DATA:-$HOME/whf}"
tz="${WHF_TZ:-UTC}"

context="$root/scripts/container"
containerfile="$context/Containerfile"
mount="$root"
data_mount="$data"

# Git Bash rewrites any argument that looks like a Unix path, so `-w /work` would arrive as a
# Windows path and the engine would report a working directory that does not exist. Turning the
# rewriting off means the host side of every bind mount has to be given as a Windows path — which is
# what a podman machine expects in any case.
case "$(uname -s)" in
MINGW* | MSYS* | CYGWIN*)
    export MSYS_NO_PATHCONV=1
    windows=1
    ;;
*) windows=0 ;;
esac

mkdir -p "$data"
if [ "$windows" -eq 1 ]; then
    mount="$(cygpath -w "$root")"
    data_mount="$(cygpath -w "$data")"
    context="$(cygpath -w "$context")"
    containerfile="$(cygpath -w "$containerfile")"
fi

if [ -z "${CONTAINER_SOCK+x}" ]; then
    if [ "$podman" -eq 1 ]; then
        # Ask podman where its socket is rather than guessing: rootless it lives under the user's
        # own runtime directory, and on Windows it is a path inside the machine that nothing on the
        # host could name.
        CONTAINER_SOCK="$("$engine" info --format '{{.Host.RemoteSocket.Path}}' 2>/dev/null || true)"
        CONTAINER_SOCK="${CONTAINER_SOCK#unix://}"
        # Only worth guessing where a Unix host's rootless socket lives. On Windows the uid is the
        # host's, not the machine's, so a guess there names a path that cannot exist: leave the
        # socket out instead and let check_socket say the tests will skip.
        if [ -z "$CONTAINER_SOCK" ] && [ "$windows" -eq 0 ]; then
            CONTAINER_SOCK="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/podman/podman.sock"
        fi
    else
        CONTAINER_SOCK=/var/run/docker.sock
    fi
fi

exists() { "$engine" inspect --type container "$name" >/dev/null 2>&1; }
running() { [ "$("$engine" inspect -f '{{.State.Running}}' "$name" 2>/dev/null || true)" = true ]; }

ensure_image() {
    if "$engine" inspect --type image "$image" >/dev/null 2>&1; then
        return
    fi
    # `inspect` fails the same way whether the image is absent or the engine is unreachable, and
    # after a reboot of Windows the second is the likely one. Building then fails with an error
    # about the Containerfile, which is the wrong place to look: ask the engine how it is first.
    local why
    if ! why="$("$engine" info 2>&1 >/dev/null)"; then
        echo "the container engine is not reachable: $why" >&2
        if [ "$podman" -eq 1 ]; then
            echo "start its machine with: podman machine start" >&2
        fi
        exit 1
    fi
    echo "==> building $image"
    "$engine" build -t "$image" -f "$containerfile" "$context"
}

# A bind mount whose source does not exist is not an error: the engine creates it as an empty
# directory. A wrong socket path therefore leaves a directory where the engine should be, and the
# only symptom is that the PostgreSQL tests quietly skip themselves. Say so at the door instead.
check_socket() {
    if [ -z "$CONTAINER_SOCK" ]; then
        echo "note: no engine socket mounted, so the gate will fail: every database test needs the engine"
        return
    fi
    if ! "$engine" exec "$name" test -S /var/run/docker.sock; then
        echo "warning: /var/run/docker.sock in the box is not a socket, so Testcontainers has no" >&2
        echo "         engine and the gate will fail: every database test needs the engine. CONTAINER_SOCK=$CONTAINER_SOCK" >&2
        if [ "$podman" -eq 1 ]; then
            echo "         podman reports: $("$engine" info --format '{{.Host.RemoteSocket.Path}}' 2>&1)" >&2
        fi
    fi
}

create() {
    echo "==> creating $name from $image"
    local args=(
        run -d --name "$name" --hostname "$name"
        # A crash brings it back; `stop` keeps it stopped. Under docker that is what distinguishes
        # unless-stopped from always; podman documents the two as the same thing, and neither
        # survives a restart of the engine without podman-restart.service, which is not enabled.
        --restart unless-stopped
        # Testcontainers publishes a port on the engine's host and then connects to it on
        # localhost, which only agrees with the box when the box shares that network.
        --network host
        -v "$mount:/work"
        -v "$data_mount:/data"
        -v "$m2_volume:/root/.m2"
        -v "$cache_volume:/root/.cache"
        # uv keeps its downloaded Pythons under ~/.local/share/uv, not in ~/.cache, and the Copilot
        # SDK unpacks its runtime under ~/.copilot. Both paths are the tools' own choice and neither
        # moves, so each needs a volume to survive `rm`.
        -v "$uv_volume:/root/.local/share/uv"
        -v "$copilot_volume:/root/.copilot"
        # gh writes the token from `gh auth login` to ~/.config/gh/hosts.yml. Keeping it means a
        # rebuild does not send you back to the browser for another device code.
        -v "$gh_volume:/root/.config/gh"
        -w /work
        -e "TZ=$tz"
    )
    if [ -n "$CONTAINER_SOCK" ]; then
        args+=(-v "$CONTAINER_SOCK:/var/run/docker.sock")
        args+=(-e DOCKER_HOST=unix:///var/run/docker.sock)
        # Ryuk, Testcontainers' reaper, wants privileges rootless podman does not grant it, so it is
        # off for either engine: a run killed part way can leave a postgres container behind.
        args+=(-e TESTCONTAINERS_RYUK_DISABLED=true)
    fi
    # A bad socket path shows up here, as an engine error about a file that does not exist, and the
    # engine has no reason to mention the variable it came from. Say which one it was.
    local code=0
    "$engine" "${args[@]}" "$@" "$image" >/dev/null || code=$?
    if [ "$code" -ne 0 ]; then
        if [ -n "$CONTAINER_SOCK" ]; then
            echo "could not create $name. If the error above names" >&2
            echo "  $CONTAINER_SOCK" >&2
            echo "then that is CONTAINER_SOCK: the engine's socket is somewhere else, or pass" >&2
            echo "CONTAINER_SOCK= to run the box without the gate." >&2
            if [ "$podman" -eq 1 ]; then
                echo "podman reports: $("$engine" info --format '{{.Host.RemoteSocket.Path}}' 2>&1)" >&2
            fi
        fi
        return "$code"
    fi
}

up() {
    ensure_image
    if exists; then
        if running; then
            echo "$name is already up"
        else
            echo "==> starting $name"
            "$engine" start "$name" >/dev/null
        fi
    else
        create "$@"
    fi
    check_socket
    echo "$name is up: bash scripts/devbox.sh shell"
}

case "${1:-}" in
up)
    shift
    up "$@"
    ;;
shell)
    up
    # A login shell so that /etc/profile.d prints what is mounted.
    exec "$engine" exec -it "$name" bash -l
    ;;
exec)
    shift
    [ $# -gt 0 ] || {
        echo "usage: bash scripts/devbox.sh exec <cmd> [args...]" >&2
        exit 2
    }
    up >/dev/null
    # -i always, or `devbox.sh exec psql -h localhost -U workloadhub workloadhub < query.sql` reads end of file and succeeds
    # having done nothing. -t only when there is a terminal to pass on: forcing it into a pipe
    # fills the output with escape codes and makes `devbox.sh exec ... | grep` useless.
    if [ -t 0 ]; then
        exec "$engine" exec -it "$name" "$@"
    fi
    exec "$engine" exec -i "$name" "$@"
    ;;
status)
    if ! exists; then
        echo "$name does not exist: bash scripts/devbox.sh up"
        exit 0
    fi
    "$engine" ps -a --filter "name=^${name}$" \
        --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
    echo
    "$engine" inspect -f 'mounts:{{range .Mounts}}{{printf "\n  %s -> %s" .Source .Destination}}{{end}}' "$name"
    echo
    if running; then check_socket; fi
    pg="${WHF_PG_CONTAINER:-whf-postgres}"
    if "$engine" inspect -f '{{.State.Running}}' "$pg" 2>/dev/null | grep -q true; then echo "local PostgreSQL: $pg is running (scripts/postgres.sh status)"; else echo "local PostgreSQL: $pg is not running (bash scripts/postgres.sh up)"; fi
    ;;
stop)
    exists || {
        echo "$name does not exist"
        exit 0
    }
    "$engine" stop "$name"
    ;;
restart)
    exists || {
        echo "$name does not exist: bash scripts/devbox.sh up"
        exit 1
    }
    "$engine" restart "$name"
    ;;
rm)
    exists || {
        echo "$name does not exist"
        exit 0
    }
    "$engine" rm -f "$name"
    echo "removed $name; the $m2_volume, $cache_volume, $uv_volume, $copilot_volume and $gh_volume volumes are kept"
    ;;
rebuild)
    echo "==> rebuilding $image"
    "$engine" build --pull -t "$image" -f "$containerfile" "$context"
    # Only now. `--pull` and apt-get both need the network, which on this machine is the fragile
    # part, and a failed rebuild should leave the box you were working in running.
    if exists; then "$engine" rm -f "$name" >/dev/null; fi
    up
    ;;
esac
