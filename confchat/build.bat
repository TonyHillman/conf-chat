@echo off
setlocal
cd /d "%~dp0"
if not exist out mkdir out
javac -encoding UTF-8 -cp "lib\openchord.jar" -d out src\confchat\*.java
if errorlevel 1 (
  echo Build FAILED.
  exit /b 1
)
echo Build OK. Start the chat with run.bat
