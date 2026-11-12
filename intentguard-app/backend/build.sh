#!/usr/bin/env bash
# Usage: ./build.sh [build|test|run|jar]
set -euo pipefail
cd "$(dirname "$0")"
OUT=out
cmd="${1:-build}"
compile() { rm -rf "$OUT"; mkdir -p "$OUT"; javac -Xlint:all -d "$OUT" $(find src -name '*.java'); }
case "$cmd" in
  build) compile; echo "Compiled to $OUT/" ;;
  test)  compile; java -cp "$OUT" com.intentguard.TestRunner ;;
  run)   compile; exec java -cp "$OUT" com.intentguard.Server ;;
  jar)   compile; jar --create --file intentguard.jar --main-class com.intentguard.Server -C "$OUT" . ; echo "Built intentguard.jar (run: java -jar intentguard.jar)" ;;
  *) echo "usage: $0 [build|test|run|jar]"; exit 1 ;;
esac
