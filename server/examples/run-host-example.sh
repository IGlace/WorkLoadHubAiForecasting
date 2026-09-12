#!/usr/bin/env bash
# Compiles and runs server/examples/HostExample.java against a SQLite database.
#
#   bash server/examples/run-host-example.sh                       # list the teams
#   bash server/examples/run-host-example.sh --team <uuid>         # the whole round trip
#   bash server/examples/run-host-example.sh --help                # the example's own options
#
# Run it inside the development container (`bash scripts/devbox.sh shell`): that is where Java, Maven and
# XGBoost's libgomp are, and the Copilot SDK runtime is pinned to linux-x64. The default database is
# /data/workloadhub.db, the seeded one; seed it first with `forecast-cli seed` if it is not there.
#
# No module is added to the build for this: the file is compiled by Java 21's single-file source launcher
# (JEP 330) against forecast-core's own compiled classes and its runtime dependencies. The repackaged CLI jar
# cannot serve as a classpath entry, because its dependencies live under BOOT-INF/lib where only Boot's own
# loader can find them.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
server="$(dirname "$here")"
cp_file="$server/forecast-core/target/example-classpath.txt"
classes="$server/forecast-core/target/classes"

cd "$server"
if [ ! -d "$classes" ] || [ -n "$(find forecast-core/src/main -newer "$classes" -name '*.java' -print -quit 2>/dev/null)" ]; then
    echo "==> compiling forecast-core" >&2
    mvn -B -q -pl forecast-core -DskipTests compile
fi
if [ ! -f "$cp_file" ] || [ forecast-core/pom.xml -nt "$cp_file" ] || [ pom.xml -nt "$cp_file" ]; then
    echo "==> resolving the runtime classpath" >&2
    mvn -B -q -pl forecast-core dependency:build-classpath \
        -Dmdep.outputFile=target/example-classpath.txt -Dmdep.includeScope=runtime
fi

exec java --class-path "$classes:$(cat "$cp_file")" "$here/HostExample.java" "$@"
