@echo off
echo Initializing Visual Studio environment...
call "D:\Microsoft Visual Studio\18\Community\Common7\Tools\VsDevCmd.bat"
if %errorlevel% neq 0 (
    echo ERROR: Failed to initialize Visual Studio environment
    pause
    exit /b 1
)
echo VS environment ready!
cd /d D:\TestProjects\Penpot-desktop
echo Running cargo check...
cargo check --manifest-path src-tauri\Cargo.toml
echo Build complete!
pause
