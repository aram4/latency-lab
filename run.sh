#!/usr/bin/env bash
# Build + run without Maven/Gradle so there's nothing between you and the JVM flags.
#
#   ./run.sh verify                          correctness gate (run before trusting any numbers)
#   ./run.sh bench                           naive vs fast, closed loop
#   ./run.sh bench --mode open --rate 200000 open loop (coordinated-omission aware)
#   JVM_OPTS="-XX:+UseZGC" ./run.sh bench --label zgc   try a different GC; label tags the results
set -euo pipefail
cd "$(dirname "$0")"

OUT=build/classes
rm -rf "$OUT" && mkdir -p "$OUT"
javac --release 21 -d "$OUT" $(find src/main/java -name '*.java')

# Fixed heap + pre-touched pages: no heap resizing or first-touch page faults mid-run.
DEFAULT_OPTS="-Xms2g -Xmx2g -XX:+AlwaysPreTouch"
JVM_OPTS="${JVM_OPTS:-}"

cmd="${1:-bench}"; shift || true
case "$cmd" in
  verify) exec java $DEFAULT_OPTS $JVM_OPTS -cp "$OUT" lab.harness.Verify "$@" ;;
  bench)  exec java $DEFAULT_OPTS $JVM_OPTS -cp "$OUT" lab.harness.Bench "$@" ;;
  *) echo "usage: $0 {verify|bench} [args]"; exit 1 ;;
esac
