#!/usr/bin/env bash
# Runs the experiment driver, which builds an experiment database on PostgreSQL.
#
#   bash server/tools/experiment.sh --help                                   # the six commands
#   bash server/tools/experiment.sh init-db
#   bash server/tools/experiment.sh seed --synthetic --users 40 --out /tmp/seed.json
#   bash server/tools/experiment.sh import /tmp/seed.json
#   bash server/tools/experiment.sh prepare ~/export.json --out ~/prepared.json   # transitional; see server/README.md
#   bash server/tools/experiment.sh fixture                                  # regenerate the committed test fixture
#
# Run it inside the development container (`bash scripts/devbox.sh shell`): that is where Java, Maven and
# XGBoost's libgomp are. The database is --url/--user/--password, else WHF_DB_URL/WHF_DB_USER/WHF_DB_PASSWORD,
# else the local one. File arguments are resolved against your working directory, not the repository.
#
# The driver is a class of forecast-tools, compiled by the gate; this script only resolves the classpath and
# runs it.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
classpath="$(bash "$here/tools-classpath.sh")"

# `fixture` writes forecast-core's committed test fixture; the path is the repository's, whatever the
# working directory, unless --out says otherwise.
if [ "${1:-}" = fixture ] && ! printf '%s\n' "$@" | grep -qx -- '--out'; then
    set -- "$@" --out "$here/../forecast-core/src/test/resources/fixtures"
fi

exec java --class-path "$classpath" com.workloadhub.forecast.tools.Experiment "$@"
