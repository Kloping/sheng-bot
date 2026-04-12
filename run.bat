@REM @author: github-kloping
@echo off
setlocal enabledelayedexpansion

if "%~1"=="" (
    echo [cmd] No Parameters, Default Skip Compilation.
) else if "%~1"=="false" (
    echo [cmd] Skip Compilation.
) else if "%~1"=="cc" (
    echo [cmd] Only Compilation.
    git pull
    call mvn clean compile
) else (
    echo [cmd] Start Clean And Copy-dependencies Compilation.
    echo [cmd] Delay 3s after rd /s /q libs
    echo [cmd] 3s
    timeout /t 1 /nobreak >nul
    echo [cmd] 2s
    timeout /t 1 /nobreak >nul
    echo [cmd] 1s
    timeout /t 1 /nobreak >nul

    if exist libs\ (
        rd /s /q libs
    )

    git pull
    call mvn clean dependency:copy-dependencies -DoutputDirectory=libs compile
)

java -Dfile.encoding=UTF-8 -cp "./target/classes;./libs/*" top.kloping.code.ShengBotApplication --spring.profiles.active=dev
endlocal
