@echo off
echo ================================
echo Cleaning build artifacts...
echo ================================
echo.

REM Remove build directories
if exist "build" (
    echo Removing build directory...
    rmdir /s /q build
    echo Build directory removed!
)

REM Remove old class files
if exist "build\classes" (
    echo Removing old class files...
    rmdir /s /q build\classes
    echo Old class files removed!
)

echo.
echo ================================
echo Clean completed!
echo ================================
echo.
echo Please run build.bat to rebuild the plugin
pause

