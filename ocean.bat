@echo off
setlocal enabledelayedexpansion
@chcp 65001 > nul

REM ============================================================================
REM Ocean Programming Language Launcher
REM ============================================================================

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

REM Find Ocean-all.jar in standard SDK / dev locations
set "OCEAN_JAR="

if defined OCEAN_HOME (
    if exist "%OCEAN_HOME%\lib\Ocean-all.jar" set "OCEAN_JAR=%OCEAN_HOME%\lib\Ocean-all.jar"
    if not defined OCEAN_JAR if exist "%OCEAN_HOME%\build\libs\Ocean-all.jar" set "OCEAN_JAR=%OCEAN_HOME%\build\libs\Ocean-all.jar"
    if not defined OCEAN_JAR if exist "%OCEAN_HOME%\Ocean-all.jar" set "OCEAN_JAR=%OCEAN_HOME%\Ocean-all.jar"
)

if not defined OCEAN_JAR if exist "%SCRIPT_DIR%\..\lib\Ocean-all.jar" set "OCEAN_JAR=%SCRIPT_DIR%\..\lib\Ocean-all.jar"
if not defined OCEAN_JAR if exist "%SCRIPT_DIR%\lib\Ocean-all.jar" set "OCEAN_JAR=%SCRIPT_DIR%\lib\Ocean-all.jar"
if not defined OCEAN_JAR if exist "%SCRIPT_DIR%\..\build\libs\Ocean-all.jar" set "OCEAN_JAR=%SCRIPT_DIR%\..\build\libs\Ocean-all.jar"
if not defined OCEAN_JAR if exist "%SCRIPT_DIR%\build\libs\Ocean-all.jar" set "OCEAN_JAR=%SCRIPT_DIR%\build\libs\Ocean-all.jar"
if not defined OCEAN_JAR if exist "%SCRIPT_DIR%\Ocean-all.jar" set "OCEAN_JAR=%SCRIPT_DIR%\Ocean-all.jar"

if not defined OCEAN_JAR (
    if exist "%SCRIPT_DIR%\gradlew.bat" (
        echo [INFO] Ocean-all.jar not found. Building compiler with Gradle...
        pushd "%SCRIPT_DIR%"
        call gradlew.bat shadowJar -q
        popd
        if exist "%SCRIPT_DIR%\build\libs\Ocean-all.jar" (
            set "OCEAN_JAR=%SCRIPT_DIR%\build\libs\Ocean-all.jar"
        )
    ) else if exist "%SCRIPT_DIR%\..\gradlew.bat" (
        echo [INFO] Ocean-all.jar not found. Building compiler with Gradle...
        pushd "%SCRIPT_DIR%\.."
        call gradlew.bat shadowJar -q
        popd
        if exist "%SCRIPT_DIR%\..\build\libs\Ocean-all.jar" (
            set "OCEAN_JAR=%SCRIPT_DIR%\..\build\libs\Ocean-all.jar"
        )
    )
)

if not defined OCEAN_JAR (
    echo Error: Ocean-all.jar could not be found. >&2
    echo Please build the project with 'gradlew shadowJar' or set OCEAN_HOME. >&2
    exit /b 1
)

where java >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo Error: Java runtime not found in PATH. >&2
    echo Please install Java 17 or higher and ensure 'java' is accessible. >&2
    exit /b 1
)

set "QUIET=true"
if not "%OCEAN_DEBUG%"=="" set "QUIET=false"

java "-Docean.home=%SCRIPT_DIR%" -Dfile.encoding=UTF-8 -Dquiet=%QUIET% -cp "%OCEAN_JAR%" ocean.compiler.OceanRunnerV3 %*
exit /b %ERRORLEVEL%
