@echo off
rem Compiles the backend into backend\out (no Maven needed).
cd /d "%~dp0.."
if exist out rmdir /s /q out
mkdir out
dir /s /b src\*.java > out\sources.txt
javac -encoding UTF-8 -d out @out\sources.txt || exit /b 1
echo Backend compiled to backend\out
