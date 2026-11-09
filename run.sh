#!/usr/bin/env bash
# Compile and start Quanta AI News. Requires JDK 21+. No other dependencies.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out
javac -d out $(find src/main/java -name '*.java')
exec java -cp out news.Main
