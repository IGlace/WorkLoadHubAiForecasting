#!/usr/bin/env bash
# Prints the classpath the two programs of forecast-tools run against: both modules' compiled classes
# followed by the tools module's runtime dependencies, compiling and resolving first if either is
# missing or stale.
#
#   cp="$(bash server/tools/tools-classpath.sh)" && java --class-path "$cp" com.workloadhub.forecast.tools.Experiment --help
#
# server/examples/run-host-example.sh and server/tools/experiment.sh both use it. Progress goes to stderr so
# only the classpath lands on stdout, and nothing here changes the caller's working directory.
set -euo pipefail

server="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cp_file="$server/forecast-tools/target/tools-classpath.txt"
core_classes="$server/forecast-core/target/classes"
tools_classes="$server/forecast-tools/target/classes"

(
    cd "$server"
    if [ ! -d "$tools_classes" ] || [ ! -d "$core_classes" ] \
        || [ -n "$(find forecast-core/src/main forecast-tools/src/main -newer "$tools_classes" -name '*.java' -print -quit 2>/dev/null)" ]; then
        echo "==> compiling forecast-core and forecast-tools" >&2
        mvn -B -q -pl forecast-tools -am -DskipTests compile
    fi
    if [ ! -f "$cp_file" ] || [ forecast-tools/pom.xml -nt "$cp_file" ] || [ forecast-core/pom.xml -nt "$cp_file" ] || [ pom.xml -nt "$cp_file" ]; then
        echo "==> resolving the runtime classpath" >&2
        # -am and package, not the goal alone: forecast-tools depends on forecast-core, the gate runs `verify`
        # and never installs it, so without core's jar in this same reactor the resolution fails outright.
        mvn -B -q -pl forecast-tools -am -DskipTests package dependency:build-classpath \
            -Dmdep.outputFile=target/tools-classpath.txt -Dmdep.includeScope=runtime
    fi
)

printf '%s:%s:%s\n' "$tools_classes" "$core_classes" "$(cat "$cp_file")"
