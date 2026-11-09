@echo off
REM Compile and start Quanta AI News. Requires JDK 21+.
cd /d "%~dp0"
if exist out rmdir /s /q out
dir /s /b src\main\java\*.java > sources.txt
javac -d out @sources.txt
del sources.txt
java -cp out news.Main
