@echo off
setlocal enabledelayedexpansion

call "D:\Microsoft Visual Studio\18\Community\Common7\Tools\VsDevCmd.bat" > nul 2>&1

cd /d D:\TestProjects\Penpot-desktop\penpot-source\render-wasm

REM Use a local Emscripten SDK copy without relying on emsdk_env.bat.
set EMSDK=D:\TestProjects\Penpot-desktop\tools\emsdk
set EMSDK_NODE=%EMSDK%\node\22.16.0_64bit\bin\node.exe
set EMSDK_PYTHON=%EMSDK%\python\3.13.3_64bit\python.exe
set EM_CONFIG=%EMSDK%\.emscripten
set PATH=%EMSDK%;%EMSDK%\upstream\emscripten;%PATH%

REM Force Rust/Cargo to invoke this exact emcc.bat wrapper.
set CARGO_TARGET_WASM32_UNKNOWN_EMSCRIPTEN_LINKER=%EMSDK%\upstream\emscripten\emcc.bat

REM Build render-wasm in debug mode (change to release if needed)
set VERSION_TAG=develop
set BUILD_MODE=debug
set CARGO_BUILD_TARGET=wasm32-unknown-emscripten
set SKIA_BINARIES_URL=https://github.com/penpot/skia-binaries/releases/download/0.93.1/skia-binaries-319323662b1685a112f5-wasm32-unknown-emscripten-gl-svg-textlayout-binary-cache-webp.tar.gz
set EM_INITIAL_HEAP=268435456
set EM_MEMORY_GROWTH_GEOMETRIC_STEP=0.8
set EM_MALLOC=dlmalloc
set EMCC_CFLAGS=--no-entry --js-library src/js/wapi.js -sMALLOC=%EM_MALLOC% -sINVOKE_RUN=0 -sALLOW_TABLE_GROWTH=0 -sALLOW_MEMORY_GROWTH=1 -sINITIAL_HEAP=%EM_INITIAL_HEAP% -sMEMORY_GROWTH_GEOMETRIC_STEP=%EM_MEMORY_GROWTH_GEOMETRIC_STEP% -sERROR_ON_UNDEFINED_SYMBOLS=0 -sMAX_WEBGL_VERSION=2 -sEXPORT_NAME=createRustSkiaModule -sEXPORTED_RUNTIME_METHODS=GL,UTF8ToString,stringToUTF8,HEAPU8,HEAP32,HEAPU32,HEAPF32 -sENVIRONMENT=web -sMODULARIZE=1 -sDISABLE_EXCEPTION_CATCHING=1 -sFILESYSTEM=0 -sEXPORT_ES6=1
set EM_CACHE=D:\TestProjects\Penpot-desktop\tools\.emsdk_cache

set CARGO_PARAMS=
set EMCC_CFLAGS=-g -sASSERTIONS=1 -sVERBOSE=1 %EMCC_CFLAGS%

cargo build --target %CARGO_BUILD_TARGET%
if %errorlevel% neq 0 exit /b %errorlevel%

set DEST=..\frontend\resources\public\js
if not exist %DEST% mkdir %DEST%
if not exist %DEST%\worker mkdir %DEST%\worker

copy target\wasm32-unknown-emscripten\%BUILD_MODE%\render_wasm.js %DEST%\render-wasm.js
copy target\wasm32-unknown-emscripten\%BUILD_MODE%\render_wasm.wasm %DEST%\render-wasm.wasm

set SHARED_FILE=
for /f "delims=" %%a in ('dir /s /b target\wasm32-unknown-emscripten\render_wasm_shared.js') do set SHARED_FILE=%%a
if defined SHARED_FILE copy "%SHARED_FILE%" ..\frontend\src\app\render_wasm\api\shared.js

pnpm exec esbuild target\wasm32-unknown-emscripten\%BUILD_MODE%\render_wasm.js --log-level=error --outfile=%DEST%\worker\render.js --platform=neutral --format=iife --global-name=WasmModule
