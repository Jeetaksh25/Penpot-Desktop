@echo off
call "D:\Microsoft Visual Studio\18\Community\Common7\Tools\VsDevCmd.bat"
if %errorlevel% neq 0 (
    echo ERROR: Failed to initialize Visual Studio environment
    exit /b 1
)
echo VS environment ready!
cd /d D:\TestProjects\Penpot-desktop
cargo check --manifest-path src-tauri\Cargo.toml
