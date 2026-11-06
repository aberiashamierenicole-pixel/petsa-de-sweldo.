#!/usr/bin/env bash
# Usage: ./scripts/manage-users.sh create-admin ADMIN-001 "Administrator"
#        ./scripts/manage-users.sh create-finance FIN-001 "Finance Admin"
#        ./scripts/manage-users.sh create-login EMP-001
#        ./scripts/manage-users.sh set-password EMP-001
set -e
cd "$(dirname "$0")/.."
ls lib/*.jar >/dev/null 2>&1 || { echo "No JDBC driver found. Copy your MySQL Connector/J JAR into backend/lib/"; exit 1; }
./scripts/build.sh >/dev/null
exec java -cp "out:lib/*" com.petsa.tools.ManageUsers "$@"
