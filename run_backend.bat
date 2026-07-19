@echo off
setlocal enabledelayedexpansion

REM Setup Visual Studio environment for any native dependencies
REM (mostly not needed for the JVM backend, but harmless)
call "D:\Microsoft Visual Studio\18\Community\Common7\Tools\VsDevCmd.bat" > nul 2>&1

cd /d D:\TestProjects\Penpot-desktop

REM Ensure local data directories exist
if not exist "data\assets" mkdir "data\assets"

REM Backend configuration for offline desktop use
set PENPOT_TENANT=default
set PENPOT_HOST=localhost
set PENPOT_PUBLIC_URI=http://localhost:3449
set PENPOT_HTTP_SERVER_PORT=3449
set PENPOT_HTTP_SERVER_HOST=localhost
set PENPOT_DATABASE_URI=postgresql://localhost/penpot
set PENPOT_DATABASE_USERNAME=penpot
set PENPOT_DATABASE_PASSWORD=penpot
set PENPOT_REDIS_URI=redis://localhost/0
set PENPOT_OBJECTS_STORAGE_BACKEND=fs
set PENPOT_OBJECTS_STORAGE_FS_DIRECTORY=data\assets
set PENPOT_STORAGE_ASSETS_FS_DIRECTORY=data\assets
set PENPOT_ASSETS_PATH=/internal/assets/
set PENPOT_SECRET_KEY=desktop-local-secret-key-change-in-production
set PENPOT_FLAGS=disable-secure-session-cookies disable-email-verification disable-google-fonts-provider disable-dashboard-templates-section disable-telemetry enable-backend-worker enable-demo-users enable-cors disable-feature-render-wasm disable-render-switch disable-render-wasm-info disable-available-viewer-wasm disable-render-wasm-dpr
set PENPOT_ALLOWED_ORIGINS=http://localhost:1420 http://localhost:3449
set PENPOT_TELEMETRY_ENABLED=false

java -jar penpot-source\backend\target\penpot.jar -m app.main
