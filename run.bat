@echo off
rem Builds and starts the API. Needs config\application.properties (or PDS_DB_* env vars) and a JDBC driver JAR in lib\.
cd /d "%~dp0.."
if not exist lib\*.jar (
  echo No JDBC driver found. Copy your MySQL Connector/J JAR ^(mysql-connector-j-*.jar^) into backend\lib\
  exit /b 1
)
call scripts\build.bat || exit /b 1
java -cp "out;lib/*" com.petsa.Main
