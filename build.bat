@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM AI Vulnerability Analyzer - Build Script
REM ============================================================
REM Configuration:
REM   - JAVA_HOME: Path to Java 21 installation
REM   - MAVEN_HOME: Path to Maven installation
REM ============================================================

echo ================================
echo AI Vulnerability Analyzer - Build Script
echo ================================
echo.

REM Set Java 21 path
set "JAVA_HOME=C:\Program Files\Java\jdk-21"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: Java not found at %JAVA_HOME%
    echo Please check JAVA_HOME path in build.bat
    pause
    exit /b 1
)

REM Set Maven path
set "MAVEN_HOME=C:\apache-maven-3.6.3"
set "MAVEN_BIN=%MAVEN_HOME%\bin\mvn.cmd"

REM Set PATH to use Java 21 and Maven
set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"

REM Check Java version
echo [1/6] Checking Java installation...
echo Using Java from: %JAVA_HOME%
"%JAVA_HOME%\bin\java" -version
if !errorlevel! neq 0 (
    echo ERROR: Java version check failed!
    pause
    exit /b 1
)
echo.

REM Check if Maven exists
echo [2/6] Checking Maven installation...
if not exist "%MAVEN_BIN%" (
    echo ERROR: Maven not found at %MAVEN_BIN%
    echo Please check MAVEN_BIN path in build.bat
    pause
    exit /b 1
)

REM Check Maven version
echo Checking Maven version...
call "%MAVEN_BIN%" -version
if !errorlevel! neq 0 (
    echo ERROR: Maven version check failed!
    pause
    exit /b 1
)
echo Maven is ready.
echo.

REM Check for Burp API classes
echo [3/6] Checking for Burp API classes...
if not exist "out\production\firstExt\burp" (
    echo Warning: Burp API classes not found in out\production\firstExt\burp
    echo Build will continue, but Burp API classes may be missing in the final JAR.
) else (
    echo Burp API classes found.
)
echo.

REM Clean previous build
echo [4/6] Cleaning previous build...
call "%MAVEN_BIN%" clean >nul 2>&1
if !errorlevel! neq 0 (
    echo Warning: Clean step had issues, but continuing...
)
echo Clean completed.
echo.

REM Download dependencies
echo [5/6] Downloading dependencies...
call "%MAVEN_BIN%" dependency:resolve
if !errorlevel! neq 0 (
    echo ERROR: Failed to download dependencies!
    pause
    exit /b 1
)
echo Dependencies downloaded successfully.
echo.

REM Build project
echo [6/6] Building project...
echo This will:
echo   - Compile all source files
echo   - Copy Burp API classes (if available)
echo   - Create JAR file with dependencies
echo.
echo Starting compilation...
echo.

call "%MAVEN_BIN%" package
if !errorlevel! neq 0 (
    echo.
    echo ================================
    echo ERROR: Build failed!
    echo ================================
    echo.
    echo Please check the error messages above.
    pause
    exit /b 1
)

echo.
echo ================================
echo Build completed successfully!
echo ================================
echo.
echo JAR file location: target\ai-analyzer-1.0.0-jar-with-dependencies.jar
echo.
if exist "target\ai-analyzer-1.0.0-jar-with-dependencies.jar" (
    echo JAR file created successfully!
    dir "target\ai-analyzer-1.0.0-jar-with-dependencies.jar" | findstr /C:"ai-analyzer"
) else (
    echo WARNING: JAR file not found at expected location!
)
echo.
echo Please load this JAR file into Burp Suite
echo.
pause
