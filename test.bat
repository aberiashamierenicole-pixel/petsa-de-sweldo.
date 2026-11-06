@echo off
rem Runs the unit tests (payroll formulas, password hashing, JSON). They need no database.
cd /d "%~dp0.."
if exist out-test rmdir /s /q out-test
mkdir out-test
dir /s /b src\*.java tests\com\petsa\api\*.java > out-test\sources.txt
javac -encoding UTF-8 -d out-test @out-test\sources.txt || exit /b 1
java -cp out-test com.petsa.api.PayrollCalculatorTest
rmdir /s /q out-test
