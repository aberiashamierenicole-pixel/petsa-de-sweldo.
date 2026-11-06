@echo off
rem Usage: scripts\manage-users.bat create-admin ADMIN-001 "Administrator"
rem        scripts\manage-users.bat create-finance FIN-001 "Finance Admin"
rem        scripts\manage-users.bat create-login EMP-001
rem        scripts\manage-users.bat set-password EMP-001
cd /d "%~dp0.."
if not exist lib\*.jar (
  echo No JDBC driver found. Copy your MySQL Connector/J JAR into backend\lib\
  exit /b 1
)
call scripts\build.bat >nul || exit /b 1
java -cp "out;lib/*" com.petsa.tools.ManageUsers %*
