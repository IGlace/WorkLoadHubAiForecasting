#!/usr/bin/env bash
# Runs server/tools/Experiment.java, which builds an experiment database on PostgreSQL.
#
#   bash server/tools/experiment.sh --help                                   # the four commands
#   bash server/tools/experiment.sh init-db
#   bash server/tools/experiment.sh seed --synthetic --users 40 --out /tmp/seed.json
#   bash server/tools/experiment.sh import /tmp/seed.json
#
# Run it inside the development container (`bash scripts/devbox.sh shell`): that is where Java, Maven and
# XGBoost's libgomp are. The database is --url/--user/--password, else WHF_DB_URL/WHF_DB_USER/WHF_DB_PASSWORD,
# else the local one. File arguments are resolved against your working directory, not the repository.
#
# No module is added to the build for this: the file is compiled by Java 21's single-file source launcher
# (JEP 330) against forecast-core's own compiled classes and its runtime dependencies, the same way
# server/examples/run-host-example.sh runs the sample host. Until 2026-09-12 this was forecast-cli, a second
# Maven module wrapping picocli around core classes that are all public already.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
classpath="$(bash "$here/core-classpath.sh")"

exec java --class-path "$classpath" "$here/Experiment.java" "$@"
