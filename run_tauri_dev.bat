@echo off
REM Initialize Visual Studio MSVC development environment
call "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\Common7\Tools\VsDevCmd.bat"

REM Remove Git's /usr/bin from PATH (conflicts with MSVC link.exe)
set PATH=%PATH:C:\Program Files\Git\usr\bin;=%
set PATH=%PATH:C:\Program Files (x86)\Git\usr\bin;=%

echo.
echo ========================================
echo VS Environment initialized!
echo Using linker:
where link.exe
echo.
echo MSVC compiler:
where cl.exe
echo ========================================
echo.

REM Navigate to project and run Tauri dev
cd /d D:\TestProjects\Penpot-desktop
echo Running npm run tauri dev...
npm run tauri dev
