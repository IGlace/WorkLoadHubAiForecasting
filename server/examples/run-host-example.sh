#!/usr/bin/env bash
# Compiles and runs server/examples/HostExample.java against a SQLite database.
#
#   bash server/examples/run-host-example.sh                       # list the teams
#   bash server/examples/run-host-example.sh --team <uuid>         # the whole round trip
#   bash server/examples/run-host-example.sh --help                # the example's own options
#
# Run it inside the development container (`bash scripts/devbox.sh shell`): that is where Java, Maven and
# XGBoost's libgomp are, and the Copilot SDK runtime is pinned to linux-x64. The default database is
# /data/workloadhub.db; if it is not there, build it with three commands, not one — `seed` only writes a JSON
# export, so it is `experiment.sh init-db --db /data/workloadhub.db`, then `experiment.sh seed --out <file>`,
# then `experiment.sh import --db /data/workloadhub.db <file>` (server/README.md, "Running experiments"). Or
# pass --db and use your own.
#
# No module is added to the build for this: the file is compiled by Java 21's single-file source launcher
# (JEP 330) against forecast-core's own compiled classes and its runtime dependencies, resolved by
# server/tools/core-classpath.sh. server/tools/experiment.sh runs the same way.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
classpath="$(bash "$(dirname "$here")/tools/core-classpath.sh")"

exec java --class-path "$classpath" "$here/HostExample.java" "$@"
