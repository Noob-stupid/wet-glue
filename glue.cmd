@echo off
setlocal
set "ROOT=%~dp0"

if not exist "%ROOT%build\Glue.class" (
    echo 首次运行，正在编译...
    javac -encoding UTF-8 -d "%ROOT%build" "%ROOT%src\Glue.java"
    if errorlevel 1 exit /b 1
)

java -Dfile.encoding=UTF-8 -cp "%ROOT%build" Glue %*
