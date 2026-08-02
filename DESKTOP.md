# Oriole Desktop (Offline, Tauri + Native design platform)

This is a Tauri v2 desktop shell around a real ClojureScript frontend and Clojure/JVM backend. All data is stored locally on your PC using PostgreSQL and Redis.

## What works now

- Native design UI served inside a Tauri window.
- Real JVM backend (104 MB uberjar) started automatically by Tauri.
- Local PostgreSQL and Redis for data and messaging.
- Offline profile/login/dashboard/workspace flow via the native backend.
- CORS configured so the Tauri dev server can talk to the backend.
- Real WebGL WASM render engine built and served locally (`render-wasm.js` / `render-wasm.wasm` / `worker/render.js`).

## What is still stubbed / needs work

- **Production packaging** is not yet tested. The dev workflow is verified.

## One-time setup

1. Install the toolchain:
   - Java 17+ (with `JAVA_HOME` set)
   - Clojure CLI (`clojure` / `deps.clj`)
   - Node.js + pnpm
   - Rust + Tauri CLI (`cargo tauri`)
   - Visual Studio 2022 Build Tools (for `link.exe`)

2. Download the portable local services into `tools/`:
   - PostgreSQL 16 x64 tiny binaries from [postgres-binaries](https://sourceforge.net/projects/postgres-binaries/files/)
     - Extract to `tools/postgres/`
   - Redis for Windows from [redis-windows](https://github.com/redis-windows/redis-windows/releases)
     - Extract so `redis-server.exe` is at `tools/redis/Redis-8.8.0-Windows-x64-msys2/redis-server.exe`

3. Build the native frontend:
   ```bat
   cd penpot-source\frontend
   pnpm install
   pnpm run build:app
   cd ..\..
   ```

4. Build the native backend uberjar:
   ```bat
   cd penpot-source\backend
   clojure -T:build clean
   clojure -T:build jar
   cd ..\..
   ```

5. Build the native WASM render engine (optional, for full canvas performance):
   ```bat
   build_render_wasm.bat
   ```
   This needs:
   - The `wasm32-unknown-emscripten` Rust target.
   - Rust **1.91.0** in `penpot-source\render-wasm` (the project pins this version).
   - Emscripten **4.0.6** in `tools\emsdk`.
   - Visual Studio 2022/2026 Build Tools (for `link.exe`).

6. Install the Tauri npm dependencies:
   ```bat
   pnpm install
   ```

## Daily dev workflow

1. Start Tauri dev — it now starts PostgreSQL + Redis + the JVM backend automatically:
   ```bat
   dev_tauri.bat
   ```
   On first run it initializes `data/postgres`, creates the `penpot` user + database, then starts Redis and the backend. On exit it stops all three.

2. (Optional) If you prefer to run the services yourself, or to stop them outside the app:
   ```bat
   start_services.bat
   stop_services.bat
   ```

3. The Oriole Desktop window should open to the native auth screen. Create a demo profile or register to start using it offline.

## Helper scripts

| Script | Purpose |
|--------|---------|
| `start_services.bat` | Initialize and start PostgreSQL + Redis |
| `stop_services.bat` | Stop PostgreSQL + Redis |
| `run_backend.bat` | Run just the JVM backend (useful for debugging) |
| `dev_tauri.bat` | Start Tauri dev with MSVC environment |
| `check_tauri.bat` | Type-check / compile the Rust side |
| `build_render_wasm.bat` | Build the native WebGL WASM renderer with Emscripten |

## How it works

- `src-tauri/src/lib.rs` starts local PostgreSQL (init + `penpot` user/db on first run) and Redis, then spawns `java -jar penpot-source/backend/target/penpot.jar -m app.main`, waits for port `3449` to accept connections, and opens the main window. On window close it stops all three services.
- The frontend is served by `npx serve` on `http://localhost:1420`.
- `scripts/inject-desktop-config.js` injects `globalThis.penpotPublicURI`, flags, and writes `js/config.js` so the frontend points at the local backend.
- The backend stores assets in `data/assets/` and keeps all Postgres data in `data/postgres/`.

## Building render-wasm (for full canvas performance)

On Windows run the helper script from the repo root:

```bat
build_render_wasm.bat
```

This produces:
- `penpot-source/frontend/resources/public/js/render-wasm.js`
- `penpot-source/frontend/resources/public/js/render-wasm.wasm`
- `penpot-source/frontend/resources/public/js/worker/render.js`
- `penpot-source/frontend/src/app/render_wasm/api/shared.js`

The script uses the exact versions the project expects:
- Rust **1.91.0** (`rustup override set 1.91.0-x86_64-pc-windows-msvc` in `penpot-source\render-wasm`).
- Emscripten **4.0.6** installed in `tools\emsdk`.