#!/usr/bin/env bash
# Runs the unit tests (payroll formulas, password hashing, JSON). They need no database.
set -e
cd "$(dirname "$0")/.."
rm -rf out-test && mkdir -p out-test
find src tests/com/petsa/api -name '*.java' > out-test/sources.txt
javac -encoding UTF-8 -d out-test @out-test/sources.txt
java -cp out-test com.petsa.api.PayrollCalculatorTest
rm -rf out-test
