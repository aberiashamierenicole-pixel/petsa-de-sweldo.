#!/usr/bin/env bash
# Builds and starts the API. Needs config/application.properties (or PDS_DB_* env vars) and a JDBC driver JAR in lib/.
set -e
cd "$(dirname "$0")/.."
ls lib/*.jar >/dev/null 2>&1 || { echo "No JDBC driver found. Copy your MySQL Connector/J JAR (mysql-connector-j-*.jar) into backend/lib/"; exit 1; }
./scripts/build.sh
exec java -cp "out:lib/*" com.petsa.Main
