@echo off
setlocal
pushd "%~dp0"
java -cp "out;lib\openchord.jar" confchat.Main %*
popd
