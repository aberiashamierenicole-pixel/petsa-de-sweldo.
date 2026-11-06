#!/usr/bin/env bash
# Compiles the backend into backend/out (no Maven needed).
set -e
cd "$(dirname "$0")/.."
rm -rf out && mkdir -p out
find src -name '*.java' > out/sources.txt
javac -encoding UTF-8 -d out @out/sources.txt
echo "Backend compiled to backend/out"
