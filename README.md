# Penpot Desktop 🎨

> A fully offline, native desktop design tool powered by the real [Penpot](https://penpot.app/) ClojureScript frontend and Clojure/JVM backend — packaged as a Windows installer via [Tauri v2](https://v2.tauri.app/).

**Penpot Desktop** brings the full power of the open-source Penpot design platform to your Windows PC, with no internet required. All data stays local — PostgreSQL, Redis, and the entire JVM backend run on your machine.

![Build Status](https://github.com/Jeetaksh25/Penpot-Desktop/actions/workflows/build.yml/badge.svg)

---

## ✨ Features

- **Native Penpot UI** — The real Penpot frontend (not a web wrapper) inside a Tauri window
- **Fully offline** — No cloud, no accounts, no telemetry. All data stored locally
- **Embedded backend** — PostgreSQL 16.4 + Redis 8.8.0 + ImageMagick 7.1.1 + JVM backend, all bundled
- **Auto-login** — No login screen; you're instantly in the workspace
- **SVG renderer** — Full canvas support (WebGL WASM renderer available with manual build)
- **Code generation** — Export designs to ReactJS, NextJS, and XML (coming soon)

---

## 📦 Download

Download the latest installer from the [Releases](https://github.com/Jeetaksh25/Penpot-Desktop/releases) page.

**System requirements:**
- Windows 10 or later (64-bit)
- 8 GB RAM recommended
- ~1 GB disk space

---

## 🔧 Development

### Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 17+ | JVM backend |
| Clojure CLI | 1.12+ | Build backend JAR |
| Node.js | 20+ | Frontend toolchain |
| pnpm | 11.9.0 | Package manager |
| Rust | stable | Tauri / native shell |
| VS 2022 Build Tools | — | C++ linker (`link.exe`) |

### Setup

```bat
:: 1. Build the backend uberjar
cd penpot-source\backend
clojure -T:build jar
cd ..\..

:: 2. Install frontend deps + build
cd penpot-source\frontend
pnpm install
pnpm run build:app
cd ..\..

:: 3. Install root deps
pnpm install

:: 4. Generate icons
node scripts\generate-icons.mjs

:: 5. Run in dev mode
pnpm tauri dev
```

### Release a new version

```bat
node build_script.mjs
```

This interactive script will:
1. Ask for the bump type (**major** / **minor** / **patch** / **custom**)
2. Update the version in `package.json`, `tauri.conf.json`, and `Cargo.toml`
3. Run the full frontend + icon build
4. Commit, tag, and push to GitHub
5. GitHub Actions then builds and publishes the installer

---

## 🏗️ CI/CD Pipeline

Every tag push (`v*`) triggers [GitHub Actions](.github/workflows/build.yml) to:

1. Build the Clojure backend JAR
2. Build the ClojureScript frontend
3. Download PostgreSQL 16.4 + Redis 8.8.0 portable binaries
4. Generate app icons
5. Compile the Tauri Rust shell
6. Produce a Windows NSIS installer
7. Upload it as a workflow artifact and GitHub Release asset

---

## 📁 Project structure

```
Penpot-Desktop/
├── src/                          # React/TypeScript UI shell (placeholder)
├── src-tauri/                    # Tauri v2 Rust backend
│   ├── src/lib.rs                # Service lifecycle (Postgres, Redis, JVM)
│   └── tauri.conf.json           # Tauri configuration
├── scripts/
│   ├── generate-icons.mjs        # Icon generator (no dependencies)
│   ├── inject-desktop-config.js  # Injects desktop runtime config into index.html
│   └── serve-penpot-proxy.js     # Same-origin proxy + auto-login
├── penpot-source/                # Upstream Penpot source (subtree)
│   ├── backend/                  # Clojure/JVM backend
│   ├── frontend/                 # ClojureScript SPA
│   ├── common/                   # Shared Clojure libs
│   ├── render-wasm/              # WebGL WASM renderer (Rust)
│   └── exporter/                 # Export engine
├── build_script.mjs              # Release automation
└── .github/workflows/build.yml   # CI pipeline
```

---

## 🧩 What's next

- **WASM WebGL renderer** — Build with Emscripten for accelerated canvas rendering
- **ReactJS / NextJS code export** — Generate production-ready component code
- **XML export** — Design system tokens and component specs
- **Plugin support** — Penpot plugin runtime integration
- **Auto-updater** — Tauri's built-in updater for seamless releases

---

## 📄 License

This project is based on [Penpot](https://github.com/penpot/penpot) (MPL-2.0) with desktop-specific additions.

Penpot Desktop — © 2026
