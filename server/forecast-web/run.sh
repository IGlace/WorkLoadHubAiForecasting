#!/usr/bin/env bash
# Builds what is stale and starts the showcase host: the front end (npm) when its sources are newer than
# ui/dist, the jar (Maven, tests skipped) when the Java sources are newer than it, then `java -jar`.
#
#   bash server/forecast-web/run.sh                         # http://localhost:8080, against the local PostgreSQL
#   bash server/forecast-web/run.sh --server.port=9090      # any Spring Boot property after the script name
#   FORECAST_WEB_DB_URL=jdbc:postgresql://host/db bash server/forecast-web/run.sh   # a database elsewhere
#   WHF_TOKEN_KEY=... bash server/forecast-web/run.sh       # your own token key instead of the generated key file
#
# Run it inside the development container (`bash scripts/devbox.sh shell`), where Java, Maven, Node and
# XGBoost's libgomp are. This application never creates or seeds a database: fill it beforehand with
# `server/tools/experiment.sh` (server/forecast-web/README.md). Front-end development instead:
# `cd server/forecast-web/ui && npm run dev` (Vite on :5173, proxying /api to :8080).
#
# THIS APPLICATION HAS NO AUTHENTICATION. It is the showcase: whoever can reach the port chooses the user to
# act as (the `X-Acting-User` header), and `GET /api/system` even names the seeded admin so the front end can
# start somewhere. The role rules are enforced for the chosen user, which is what there is to try out, but
# nothing decides *which* user a caller may be -- that is the session the WorkloadHub server brings. Keep it
# on a machine you trust, and remember that the tokens stored through its settings page are real GitHub
# credentials: `--server.address=127.0.0.1` binds it to the loopback interface (a container needs 0.0.0.0 to
# be reachable from its host, which is why that is the default).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
server="$(dirname "$here")"
ui="$here/ui"
jar="$here/target/workloadhub-forecast-web-0.1.0-SNAPSHOT.jar"

# Skip the check when the caller pointed at a database of their own: whether the local container is up is
# then the wrong question.
if [ -z "${FORECAST_WEB_DB_URL:-}" ]; then
    if ! bash "$server/../scripts/postgres.sh" status >/dev/null 2>&1; then
        cat >&2 <<'EOT'
The local PostgreSQL is not running, and this application does not create or seed a database.

    bash scripts/postgres.sh up
    bash server/tools/experiment.sh init-db
    bash server/tools/experiment.sh seed --synthetic --users 40 --out /tmp/seed.json
    bash server/tools/experiment.sh import /tmp/seed.json

Then run this script again. Point it elsewhere with FORECAST_WEB_DB_URL.
EOT
        exit 1
    fi
fi

if command -v npm >/dev/null 2>&1; then
    if [ ! -d "$ui/node_modules" ]; then
        echo "==> npm ci" >&2
        (cd "$ui" && npm ci --no-audit --no-fund --silent)
    fi
    if [ ! -f "$ui/dist/index.html" ] || [ -n "$(find "$ui/src" "$ui/index.html" -newer "$ui/dist/index.html" -print -quit 2>/dev/null)" ]; then
        echo "==> building the front end" >&2
        (cd "$ui" && npm run build --silent)
    fi
else
    echo "npm not found: the application starts without the front end (the REST surface still answers)" >&2
fi

if [ ! -f "$jar" ] || [ -n "$(find "$server/forecast-core/src/main" "$here/src/main" -newer "$jar" -name '*' -type f -print -quit 2>/dev/null)" ]; then
    echo "==> packaging forecast-web" >&2
    (cd "$server" && mvn -B -q -pl forecast-web -am -DskipTests package)
fi

cd "$here"
exec java -jar "$jar" "$@"
