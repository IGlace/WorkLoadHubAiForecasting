#!/usr/bin/env bash
# Runs server/tools/Experiment.java, which builds and scores an experiment database on SQLite.
#
#   bash server/tools/experiment.sh --help                                   # the five commands
#   bash server/tools/experiment.sh init-db --db /data/workloadhub.db
#   bash server/tools/experiment.sh seed --synthetic --users 40 --out /tmp/seed.json
#   bash server/tools/experiment.sh import --db /data/workloadhub.db /tmp/seed.json
#   bash server/tools/experiment.sh eval --db /data/workloadhub.db --windows 2
#
# Run it inside the development container (`bash scripts/devbox.sh shell`): that is where Java, Maven and
# XGBoost's libgomp are. File arguments are resolved against your working directory, not the repository.
#
# No module is added to the build for this: the file is compiled by Java 21's single-file source launcher
# (JEP 330) against forecast-core's own compiled classes and its runtime dependencies, the same way
# server/examples/run-host-example.sh runs the sample host. Until 2026-09-12 this was forecast-cli, a second
# Maven module wrapping picocli around core classes that are all public already.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
classpath="$(bash "$here/core-classpath.sh")"

exec java --class-path "$classpath" "$here/Experiment.java" "$@"
