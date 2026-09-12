#!/usr/bin/env bash
# Prints the classpath the two single-file programs run against: forecast-core's own compiled classes
# followed by its runtime dependencies, compiling and resolving first if either is missing or stale.
#
#   cp="$(bash server/tools/core-classpath.sh)" && java --class-path "$cp" some/Program.java
#
# server/examples/run-host-example.sh and server/tools/experiment.sh both use it. Progress goes to stderr so
# only the classpath lands on stdout, and nothing here changes the caller's working directory: the programs
# take file paths from the command line and must resolve them the way the user typed them.
set -euo pipefail

server="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cp_file="$server/forecast-core/target/example-classpath.txt"
classes="$server/forecast-core/target/classes"

(
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
)

printf '%s:%s\n' "$classes" "$(cat "$cp_file")"
