#!/usr/bin/env bash
# Runs the sample host against a PostgreSQL database.
#
#   bash server/examples/run-host-example.sh                       # list the teams
#   bash server/examples/run-host-example.sh --team <uuid>         # the whole round trip
#   bash server/examples/run-host-example.sh --help                # the example's own options
#
# Run it inside the development container (`bash scripts/devbox.sh shell`): that is where Java, Maven and
# XGBoost's libgomp are, and the Copilot SDK runtime is pinned to linux-x64. The default database is the local
# PostgreSQL scripts/postgres.sh runs; see server/README.md, "Running experiments", for the three commands
# that fill it. Or pass --url and use your own.
#
# The sample host is a class of forecast-tools, compiled by the gate; this script only resolves the classpath
# and runs it. server/tools/experiment.sh runs the same way.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
classpath="$(bash "$(dirname "$here")/tools/tools-classpath.sh")"

exec java --class-path "$classpath" com.workloadhub.forecast.examples.HostExample "$@"
