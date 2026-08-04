use std::io::{BufRead, BufReader};
use std::net::TcpStream;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::{Emitter, Manager};

mod proxy;

// Feature modules. `commands` is the shared foundation (F1): path helpers +
// the trivial `ping` bridge check. `llm` is the closed AI layer (F4). `fonts`
// is added in Feature 1. Each module owns its `#[tauri::command]`s; they are
// all registered together in a single `generate_handler![…]` below.
mod commands;
mod llm;
mod fonts;
mod code_export;
mod mcp_server;
mod publish;
mod plugin_registry;
mod cms_import;
mod stock_assets;
mod file_import;
mod storybook;
mod team_sharing;

/// Hide the console window for a spawned process. The Tauri app uses the
/// `windows` subsystem (GUI, no parent console), so without this every
/// console exe we launch — postgres, redis, java, initdb, psql, … — pops its
/// own visible cmd window that stays open for the long-lived ones. The flag
/// is inherited by grandchildren (e.g. `postgres.exe` under `pg_ctl`).
#[cfg(windows)]
fn silent(cmd: &mut Command) {
    use std::os::windows::process::CommandExt;
    // CREATE_NO_WINDOW = 0x08000000
    cmd.creation_flags(0x0800_0000);
}
#[cfg(not(windows))]
fn silent(_cmd: &mut Command) {}

/// Configuration for the embedded Penpot JVM backend + local services.
const BACKEND_JAR: &str = "penpot-source/backend/target/penpot.jar";
const BACKEND_PORT: u16 = 3449;
const FRONTEND_PORT: u16 = 1420;
const POSTGRES_PORT: u16 = 5432;
const REDIS_PORT: u16 = 6379;
const BACKEND_HEALTH_TIMEOUT: Duration = Duration::from_secs(180);
const SERVICE_HEALTH_TIMEOUT: Duration = Duration::from_secs(60);
const HEALTH_INTERVAL: Duration = Duration::from_millis(500);

const POSTGRES_BIN: &str = "tools/postgres/bin";
const REDIS_BIN: &str = "tools/redis/Redis-8.8.0-Windows-x64-msys2";
const JRE_DIR: &str = "tools/jre";
const IMAGEMAGICK_DIR: &str = "tools/imagemagick";

/// Tracks child processes so they can be killed on exit.
/// Postgres manages its own daemon via pg_ctl, so only the JVM and Redis
/// (run in the foreground) are held as Child handles here.
pub struct BackendState {
    pub jvm: Mutex<Option<Child>>,
    pub redis: Mutex<Option<Child>>,
}

/// Returns the project root (dev) or the resource directory (production).
///
/// **Dev mode**: walks up from the executable path (or current directory) and
/// checks for both `penpot-source/backend/target/penpot.jar` and
/// `tools/postgres/bin` — whichever exists first identifies the repo root.
/// This avoids a wrong path when the backend JAR hasn't been built yet.
///
/// **Production**: uses Tauri's `resource_dir()` which mirrors the source
/// layout thanks to `bundle.resources` in `tauri.conf.json`.
/// Returns the project root (dev) or the resource directory (production).
///
/// Strategy:
/// 1. Exe walk-up (dev) — walks parent dirs from the exe looking for
///    `src-tauri/Cargo.toml` (unique to the real repo root).
/// 2. Cwd walk-up (dev) — same check from current directory.
/// 3. Resource dir (production) — Tauri's resource_dir via app handle.
fn project_root(handle: Option<&tauri::AppHandle>) -> PathBuf {
    // Sentinel: only the real repo root has `src-tauri/Cargo.toml` (dev) or
    // the backend JAR (production, via bundle.resources).
    let is_repo_root = |dir: &Path| -> bool {
        dir.join("src-tauri/Cargo.toml").exists()
            || dir.join(BACKEND_JAR).exists()
    };

    // Tier 1 — exe walk-up (primary dev path).
    if let Ok(exe) = std::env::current_exe() {
        let mut dir = exe.parent().map(Path::to_path_buf).unwrap_or_default();
        if dir.file_name().map(|n| n == "debug" || n == "release").unwrap_or(false) {
            dir.pop();
            if dir.file_name() == Some(std::ffi::OsStr::new("target")) {
                dir.pop();
            }
            if dir.file_name() == Some(std::ffi::OsStr::new("src-tauri")) {
                dir.pop();
            }
        }
        if is_repo_root(&dir) {
            return dir;
        }
    }

    // Tier 2 — cwd walk-up (up to 5 levels).
    if let Ok(cwd) = std::env::current_dir() {
        let mut dir = cwd;
        for _ in 0..5 {
            if is_repo_root(&dir) {
                return dir;
            }
            if !dir.pop() {
                break;
            }
        }
    }

    // Tier 3 — Tauri resource dir (production via bundle.resources).
    if let Some(h) = handle {
        if let Ok(dir) = h.path().resource_dir() {
            if is_repo_root(&dir) {
                return dir;
            }
            if let Some(parent) = dir.parent() {
                if is_repo_root(parent) {
                    return parent.to_path_buf();
                }
            }
        }
    }

    // Tier 4 — last resort.
    PathBuf::from(".")
}

fn backend_jar_path(root: &Path) -> PathBuf {
    root.join(BACKEND_JAR)
}

fn postgres_bin(root: &Path, exe: &str) -> PathBuf {
    root.join(POSTGRES_BIN).join(exe)
}

fn redis_bin(root: &Path, exe: &str) -> PathBuf {
    root.join(REDIS_BIN).join(exe)
}

fn jre_bin(root: &Path, exe: &str) -> PathBuf {
    root.join(JRE_DIR).join("bin").join(exe)
}

fn imagemagick_bin(root: &Path) -> PathBuf {
    root.join(IMAGEMAGICK_DIR)
}

/// Prepend the bundled ImageMagick directory to PATH so the JVM backend can
/// resolve `magick` without requiring a system-wide ImageMagick install.
/// In dev mode, if the bundled directory is absent, the existing PATH is left
/// unchanged so a system install can still be used.
fn prepend_imagemagick_to_path(root: &Path) -> String {
    let imagemagick = imagemagick_bin(root);
    let imagemagick_s = imagemagick.to_string_lossy().to_string();
    if !imagemagick.exists() {
        return std::env::var("PATH").unwrap_or_default();
    }
    match std::env::var("PATH") {
        Ok(path) if !path.is_empty() => {
            format!("{}{}{}", imagemagick_s, if cfg!(windows) { ";" } else { ":" }, path)
        }
        _ => imagemagick_s,
    }
}

/// True when something is already accepting TCP connections on `port`.
fn port_open(port: u16) -> bool {
    TcpStream::connect(("127.0.0.1", port)).is_ok()
}

/// Poll a port until it accepts connections or the timeout elapses.
fn wait_for_port(name: &str, port: u16, timeout: Duration) -> Result<(), String> {
    let start = Instant::now();
    loop {
        if port_open(port) {
            return Ok(());
        }
        if start.elapsed() > timeout {
            return Err(format!("{} did not become ready on port {}", name, port));
        }
        std::thread::sleep(HEALTH_INTERVAL);
    }
}

/// Run a one-shot command, inheriting stdio, returning its stdout/stderr on failure.
fn run_cmd(program: &Path, args: &[&str]) -> Result<(), String> {
    let mut cmd = Command::new(program);
    cmd.args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    silent(&mut cmd);
    let output = cmd
        .output()
        .map_err(|e| format!("failed to run {}: {}", program.display(), e))?;

    if output.status.success() {
        Ok(())
    } else {
        let stdout = String::from_utf8_lossy(&output.stdout);
        let stderr = String::from_utf8_lossy(&output.stderr);
        Err(format!(
            "{} exited with {:?}\nstdout: {}\nstderr: {}",
            program.display(),
            output.status.code(),
            stdout.trim(),
            stderr.trim()
        ))
    }
}

/// True when `pg_ctl status` reports a running postmaster for our data dir.
/// This is more reliable than a TCP `port_open` probe, which races with brief
/// port unavailability during a stop/restart and can wrongly enter the start
/// branch while a stale postmaster still holds the data-dir lock.
fn pg_ctl_status(root: &Path) -> bool {
    let data_dir = root.join("data/postgres");
    let data_dir_s = data_dir.to_string_lossy().to_string();
    let mut cmd = Command::new(postgres_bin(root, "pg_ctl.exe"));
    cmd.args(["-D", data_dir_s.as_str(), "status"])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    silent(&mut cmd);
    cmd.output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

/// Launch the postmaster via `pg_ctl start` (no `-w`) and wait for `pg_ctl`
/// itself to exit. Critically, stdio is set to `null` (not piped): `pg_ctl start`
/// spawns `postgres.exe` as a long-lived grandchild that would inherit piped
/// handles and keep them open forever, making `output()` block even after
/// `pg_ctl` exits. With null stdio there is no pipe to wait on, so `wait()`
/// returns as soon as `pg_ctl` has launched the postmaster; we then poll the
/// port ourselves for readiness.
fn pg_ctl_start(root: &Path, data_dir: &str, log: &str) -> Result<(), String> {
    let mut cmd = Command::new(postgres_bin(root, "pg_ctl.exe"));
    cmd.args(["-D", data_dir, "-l", log, "start"])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    silent(&mut cmd);
    let mut child = cmd
        .spawn()
        .map_err(|e| format!("failed to run pg_ctl start: {}", e))?;
    let status = child
        .wait()
        .map_err(|e| format!("pg_ctl start wait failed: {}", e))?;
    if status.success() {
        Ok(())
    } else {
        Err(format!("pg_ctl start exited with {:?}", status.code()))
    }
}

/// Start the local PostgreSQL instance. Initializes the data directory and
/// creates the `penpot` user/database on first run. Reuses an already-running
/// postmaster for this data dir; otherwise clears any stale lock left by a
/// crashed/killed previous run and starts fresh with a bounded wait.
fn start_postgres(root: &Path) -> Result<(), String> {
    let data_dir = root.join("data/postgres");
    let log_path = root.join("data/postgres.log");
    let data_dir_s = data_dir.to_string_lossy().to_string();
    let log_s = log_path.to_string_lossy().to_string();
    std::fs::create_dir_all(&data_dir).ok();
    let pid_file = data_dir.join("postmaster.pid");

    if pg_ctl_status(root) {
        eprintln!("[penpot-desktop] PostgreSQL already running.");
    } else {
        // A stale postmaster.pid (PID no longer alive) would make pg_ctl start
        // refuse or hang; remove it before starting.
        if pid_file.exists() {
            let _ = std::fs::remove_file(&pid_file);
        }

        let fresh = !data_dir.join("PG_VERSION").exists();
        if fresh {
            eprintln!("[penpot-desktop] Initializing PostgreSQL data directory...");
            run_cmd(
                &postgres_bin(root, "initdb.exe"),
                &[
                    "-D",
                    data_dir_s.as_str(),
                    "--username=postgres",
                    "--auth=trust",
                    "--encoding=UTF8",
                    "--locale=C",
                ],
            )?;
        }

        eprintln!("[penpot-desktop] Starting PostgreSQL...");
        // Launch (pg_ctl_start returns once the postmaster is started) then
        // poll the port ourselves — pg_ctl's `-w` readiness wait is unreliable
        // on Windows, and `run_cmd`/`output()` would deadlock on the piped
        // stdio inherited by the long-lived postmaster grandchild.
        pg_ctl_start(root, data_dir_s.as_str(), log_s.as_str())?;
        if wait_for_port("PostgreSQL", POSTGRES_PORT, Duration::from_secs(30)).is_err() {
            // Recovery: stop anything still holding the lock, clear the pid,
            // and retry once.
            eprintln!("[penpot-desktop] PostgreSQL did not become ready; recovering...");
            let _ = run_cmd(
                &postgres_bin(root, "pg_ctl.exe"),
                &[
                    "-D",
                    data_dir_s.as_str(),
                    "-m",
                    "fast",
                    "-w",
                    "-t",
                    "15",
                    "stop",
                ],
            );
            let _ = std::fs::remove_file(&pid_file);
            pg_ctl_start(root, data_dir_s.as_str(), log_s.as_str())?;
            wait_for_port("PostgreSQL", POSTGRES_PORT, SERVICE_HEALTH_TIMEOUT)?;
        }
    }

    // Always ensure the penpot role + database exist (idempotent), even when
    // Postgres was started elsewhere — so a data dir initialized without the
    // role (e.g. by an older start_services.bat) is repaired every boot.
    ensure_postgres_role_db(root);

    Ok(())
}

/// Run a scalar `psql` query and return the trimmed first cell of output.
fn pg_scalar(root: &Path, sql: &str) -> Option<String> {
    let mut cmd = Command::new(postgres_bin(root, "psql.exe"));
    cmd.args(["-U", "postgres", "-h", "localhost", "-p", "5432", "-tA", "-c", sql])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    silent(&mut cmd);
    let out = cmd.output().ok()?;
    if out.status.success() {
        Some(String::from_utf8_lossy(&out.stdout).trim().to_string())
    } else {
        None
    }
}

/// Create the `penpot` role and `penpot` database if they don't already exist.
fn ensure_postgres_role_db(root: &Path) {
    if pg_scalar(root, "SELECT 1 FROM pg_roles WHERE rolname='penpot'").as_deref() != Some("1") {
        let _ = run_cmd(
            &postgres_bin(root, "createuser.exe"),
            &["-U", "postgres", "-h", "localhost", "-p", "5432", "-w", "penpot"],
        );
        eprintln!("[penpot-desktop] Created penpot role.");
    }
    if pg_scalar(root, "SELECT 1 FROM pg_database WHERE datname='penpot'").as_deref() != Some("1") {
        let _ = run_cmd(
            &postgres_bin(root, "createdb.exe"),
            &[
                "-U", "postgres", "-h", "localhost", "-p", "5432", "-O", "penpot", "penpot",
            ],
        );
        eprintln!("[penpot-desktop] Created penpot database.");
    }
}

/// Stop the local PostgreSQL instance gracefully.
fn stop_postgres(root: &Path) {
    if !port_open(POSTGRES_PORT) {
        return;
    }
    let data_dir = root.join("data/postgres");
    let data_dir_s = data_dir.to_string_lossy().to_string();
    let _ = run_cmd(
        &postgres_bin(root, "pg_ctl.exe"),
        &["-D", data_dir_s.as_str(), "-m", "fast", "-w", "-t", "15", "stop"],
    );
    eprintln!("[penpot-desktop] PostgreSQL stopped.");
}

/// Start the local Redis instance in the foreground, returning the child so it
/// can be killed on exit. No-op if something is already bound to the port.
fn start_redis(root: &Path) -> Result<Option<Child>, String> {
    if port_open(REDIS_PORT) {
        eprintln!("[penpot-desktop] Redis already running on {}.", REDIS_PORT);
        return Ok(None);
    }

    let data_dir = root.join("data/redis");
    let data_dir_s = data_dir.to_string_lossy().to_string();
    let port_s = REDIS_PORT.to_string();
    std::fs::create_dir_all(&data_dir).ok();

    eprintln!("[penpot-desktop] Starting Redis...");
    let mut cmd = Command::new(redis_bin(root, "redis-server.exe"));
    // --bind 127.0.0.1 keeps redis on loopback only, so Windows Firewall
    // never prompts (a 0.0.0.0 bind is what triggers the "redis-server.exe
    // wants network access" dialog) and no admin / firewall rule is needed.
    cmd.args([
            "--bind",
            "127.0.0.1",
            "--port",
            port_s.as_str(),
            "--loglevel",
            "notice",
            "--dir",
            data_dir_s.as_str(),
        ])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    silent(&mut cmd);
    let mut child = cmd
        .spawn()
        .map_err(|e| format!("failed to start redis: {}", e))?;

    // Drain logs so the pipe buffer never blocks redis.
    if let Some(stdout) = child.stdout.take() {
        let _ = std::thread::spawn(move || {
            for line in BufReader::new(stdout).lines().flatten() {
                eprintln!("[redis] {}", line);
            }
        });
    }
    if let Some(stderr) = child.stderr.take() {
        let _ = std::thread::spawn(move || {
            for line in BufReader::new(stderr).lines().flatten() {
                eprintln!("[redis] {}", line);
            }
        });
    }

    wait_for_port("Redis", REDIS_PORT, SERVICE_HEALTH_TIMEOUT)?;
    Ok(Some(child))
}

fn backend_env(root: &Path) -> Vec<(&'static str, String)> {
    // datoteka.fs/normalize prepends `*cwd* + sep` to any path NOT starting with
    // the separator (`\` on Windows) — including absolute `D:\…` paths. The fs
    // storage backend (penpot storage/fs.clj put-object/get-object-data) wraps
    // fs/join in a redundant OUTER fs/normalize, so a relative `data\assets`
    // becomes `D:\…\data\assets` on the first pass and gets the cwd prepended
    // AGAIN on the second → the doubled
    // `D:\Apps\Penpot Desktop\D:\Apps\Penpot Desktop\data\assets\…` that broke
    // asset serving and template import. We now patch app.storage.fs to avoid
    // the posix-only datoteka.fs/create-dir call, but the normalize doubling is
    // still present, so this remains fixed env-only: pass a drive-rooted,
    // NO-drive-letter path (`\Apps\Penpot Desktop\data\assets`). Such a path
    // starts with `\` so fs/normalize leaves it untouched at every stage (no
    // doubling), and Java resolves `\…` against the backend CWD's current drive
    // (the install drive, since spawn_backend sets cwd=root) at I/O time
    // WITHOUT adding the drive to the Path string — so reads/writes land at
    // <install>\data\assets, exactly where boot_backend creates the dir and the
    // Rust proxy reads x-accel-redirected assets from.
    let storage_dir = strip_drive_prefix(root.join("data").join("assets"));
    vec![
        // PATH must be first so later entries don't accidentally shadow it.
        ("PATH", prepend_imagemagick_to_path(root)),
        ("PENPOT_TENANT", "default".into()),
        ("PENPOT_HOST", "localhost".into()),
        // Public URI is the frontend/proxy origin so any absolute URL the
        // backend generates (assets, exports, redirects) is same-origin too.
        ("PENPOT_PUBLIC_URI", format!("http://localhost:{}", FRONTEND_PORT)),
        ("PENPOT_HTTP_SERVER_PORT", BACKEND_PORT.to_string()),
        ("PENPOT_HTTP_SERVER_HOST", "localhost".into()),
        ("PENPOT_DATABASE_URI", "postgresql://localhost/penpot".into()),
        ("PENPOT_DATABASE_USERNAME", "penpot".into()),
        ("PENPOT_DATABASE_PASSWORD", "penpot".into()),
        ("PENPOT_REDIS_URI", format!("redis://localhost:{}", REDIS_PORT)),
        ("PENPOT_OBJECTS_STORAGE_BACKEND", "fs".into()),
        ("PENPOT_OBJECTS_STORAGE_FS_DIRECTORY", storage_dir.clone()),
        ("PENPOT_STORAGE_ASSETS_FS_DIRECTORY", storage_dir),
        ("PENPOT_ASSETS_PATH", "/internal/assets/".into()),
        ("PENPOT_SECRET_KEY", "desktop-local-secret-key-change-in-production".into()),
        (
            "PENPOT_FLAGS",
            // Feature 1: `disable-google-fonts-provider` is REMOVED so the
            // frontend registers the baked Google Fonts library and the font
            // picker shows it. The proxy serves /internal/gfonts/* (online by
            // default; offline cache on demand) instead of the nginx upstream.
            "disable-secure-session-cookies disable-email-verification \
             disable-dashboard-templates-section disable-telemetry enable-backend-worker \
             enable-demo-users enable-cors disable-feature-render-wasm disable-render-switch \
             disable-render-wasm-info disable-available-viewer-wasm disable-render-wasm-dpr"
                .into(),
        ),
        (
            "PENPOT_ALLOWED_ORIGINS",
            // The SPA now runs on the same http://localhost:1420 origin as the
            // proxy (no more tauri.localhost split), so this is the API/WS
            // origin. localhost:3449 is the backend directly; tauri.localhost
            // is retained for the embedded loading page (harmless, no API).
            "http://localhost:1420 http://localhost:3449 http://tauri.localhost".into(),
        ),
        ("PENPOT_TELEMETRY_ENABLED", "false".into()),
    ]
}

/// Strip a leading `X:` Windows drive prefix from a path, returning the
/// remaining `\…`-rooted string with no drive letter. Used for the fs storage
/// env so datoteka's fs/normalize does not prepend the CWD a second time. See
/// `backend_env` for the full rationale.
fn strip_drive_prefix(p: PathBuf) -> String {
    let s = p.to_string_lossy().into_owned();
    let bytes = s.as_bytes();
    if bytes.len() >= 2 && bytes[0].is_ascii_alphabetic() && bytes[1] == b':' {
        s[2..].to_string()
    } else {
        s
    }
}

fn which_java(root: &Path) -> Result<String, String> {
    // 1. Bundled JRE (production / CI — created by jlink in build pipeline).
    let bundled = jre_bin(root, "java.exe");
    if bundled.exists() {
        return Ok(bundled.to_string_lossy().into_owned());
    }
    // 2. System JAVA_HOME.
    if let Ok(java_home) = std::env::var("JAVA_HOME") {
        let exe = PathBuf::from(&java_home).join("bin/java.exe");
        if exe.exists() {
            return Ok(exe.to_string_lossy().into_owned());
        }
    }
    // 3. Fallback to PATH.
    Ok("java".into())
}

fn spawn_backend(root: &Path) -> Result<Child, String> {
    let jar = backend_jar_path(root);
    if !jar.exists() {
        return Err(format!("Backend uberjar not found: {}", jar.display()));
    }

    let java = which_java(root)?;
    let mut cmd = Command::new(java);
    cmd.current_dir(&root)
        .arg("-jar")
        .arg(&jar)
        .arg("-m")
        .arg("app.main");

    // In the packaged build the app uses the windows GUI subsystem (no
    // console), so eprintln is lost and the JVM's exception stacktraces — the
    // actual cause of any backend 500 / template-import / asset-write failure
    // — would be invisible. Redirect backend stdout+stderr to
    // <root>/data/backend.log (truncated each boot so the file reflects the
    // current run; append mode keeps concurrent stdout/stderr writes atomic).
    // The user can share this file to root-cause backend errors without
    // DevTools. Dev keeps the piped+eprintln path so logs still appear in the
    // `npm run dev` terminal.
    let log_path = root.join("data").join("backend.log");
    if cfg!(debug_assertions) {
        cmd.stdout(Stdio::piped()).stderr(Stdio::piped());
    } else {
        let _ = std::fs::create_dir_all(root.join("data"));
        let _ = std::fs::remove_file(&log_path); // fresh log each boot
        match std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&log_path)
        {
            Ok(log_file) => match log_file.try_clone() {
                Ok(log_err) => {
                    cmd.stdout(Stdio::from(log_file));
                    cmd.stderr(Stdio::from(log_err));
                }
                Err(_) => {
                    cmd.stdout(Stdio::from(log_file));
                    cmd.stderr(Stdio::null());
                }
            },
            Err(e) => {
                eprintln!(
                    "[penpot-desktop] Could not open backend log {}: {}",
                    log_path.display(),
                    e
                );
                cmd.stdout(Stdio::null()).stderr(Stdio::null());
            }
        }
    }
    silent(&mut cmd);

    for (key, value) in backend_env(root) {
        cmd.env(key, value);
    }

    let mut child = cmd.spawn().map_err(|e| format!("Failed to start backend: {}", e))?;

    if cfg!(debug_assertions) {
        // Dev: drain piped stdout/stderr to the terminal so backend logs are
        // visible in the `npm run dev` console. (Release writes to backend.log.)
        if let Some(stdout) = child.stdout.take() {
            std::thread::spawn(move || {
                for line in BufReader::new(stdout).lines().flatten() {
                    eprintln!("[penpot-backend] {}", line);
                }
            });
        }
        if let Some(stderr) = child.stderr.take() {
            std::thread::spawn(move || {
                for line in BufReader::new(stderr).lines().flatten() {
                    eprintln!("[penpot-backend] {}", line);
                }
            });
        }
    } else {
        eprintln!(
            "[penpot-desktop] JVM backend started (PID {}); log: {}",
            child.id(),
            log_path.display()
        );
    }

    Ok(child)
}

/// Evaluate JavaScript on the main webview window. No-op if the window
/// is gone (e.g. the user closed it during boot). Safe to call from the
/// boot thread — Tauri dispatches eval onto the webview.
fn eval_main(handle: &tauri::AppHandle, js: &str) {
    if let Some(win) = handle.get_webview_window("main") {
        let _ = win.eval(js);
    }
}

/// Update the status line on the loading page so the user sees boot progress
/// (and failures) instead of a blank, invisible background process.
fn set_boot_status(handle: &tauri::AppHandle, msg: &str) {
    let json = serde_json::to_string(msg).unwrap_or_else(|_| "\"\"".into());
    let js = format!(
        "try{{var el=document.getElementById('boot-status');if(el)el.textContent={json};}}catch(e){{}}"
    );
    eval_main(handle, &js);
}

/// Boot the local services, then the JVM backend, then open the window.
/// Runs in a background thread so the Tauri setup() returns immediately.
fn boot_backend(handle: tauri::AppHandle) {
    let root = project_root(Some(&handle));

    // 1. Local data stores — everything the app writes lives under data/.
    let _ = std::fs::create_dir_all(root.join("data/assets"));

    // 2. PostgreSQL (init + create penpot db/user on first run).
    set_boot_status(&handle, "Starting local database…");
    if let Err(e) = start_postgres(&root) {
        eprintln!("[penpot-desktop] PostgreSQL start failed: {}", e);
        set_boot_status(&handle, &format!("Failed to start PostgreSQL: {e}"));
        return;
    }

    // 3. Redis (foreground child, killed on exit).
    set_boot_status(&handle, "Starting local cache…");
    let redis_child = match start_redis(&root) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("[penpot-desktop] Redis start failed: {}", e);
            set_boot_status(&handle, &format!("Failed to start Redis: {e}"));
            return;
        }
    };
    if let Some(state) = handle.try_state::<BackendState>() {
        let _ = state.redis.lock().map(|mut g| *g = redis_child);
    }

    // 4. JVM backend (the real Penpot API + Sente + binary file store).
    match spawn_backend(&root) {
        Ok(child) => {
            eprintln!("[penpot-desktop] JVM backend started (PID {}).", child.id());
            if let Some(state) = handle.try_state::<BackendState>() {
                let _ = state.jvm.lock().map(|mut g| *g = Some(child));
            }
            set_boot_status(&handle, "Starting Ovion backend…");
            match wait_for_port("Backend", BACKEND_PORT, BACKEND_HEALTH_TIMEOUT) {
                Ok(()) => {
                    eprintln!(
                        "[penpot-desktop] Backend ready at http://localhost:{}.",
                        BACKEND_PORT
                    );

                    // 5. The same-origin proxy was started in setup() (static
                    //    only, so the loading page could load immediately).
                    //    Now that the JVM is ready, arm auto-login (registers
                    //    / logs in the fixed local account on first boot) so
                    //    the SPA never shows a login screen, then navigate the
                    //    loading window to the SPA root. `/` resolves to the
                    //    window's own origin (http://localhost:1420) in both
                    //    dev (Node proxy) and release (Rust proxy), so this is
                    //    NOT a cross-origin navigation. (Dev skips auto-login;
                    //    the Node proxy / login screen handles dev.)
                    if !cfg!(debug_assertions) {
                        proxy::enable_auto_login();
                    }

                    let _ = handle.emit("backend-ready", ());
                    set_boot_status(&handle, "Loading workspace…");
                    eval_main(&handle, "try{location.replace('/')}catch(e){}");
                }
                Err(e) => {
                    eprintln!("[penpot-desktop] Backend failed to become ready: {}", e);
                    set_boot_status(&handle, &format!("Backend did not become ready: {e}"));
                }
            }
        }
        Err(e) => {
            eprintln!("[penpot-desktop] Failed to start backend: {}", e);
            set_boot_status(&handle, &format!("Failed to start backend: {e}"));
        }
    }
}

fn shutdown_services(app: &tauri::AppHandle) {
    if let Some(state) = app.try_state::<BackendState>() {
        if let Ok(mut g) = state.redis.lock() {
            if let Some(mut child) = g.take() {
                let _ = child.kill();
                let _ = child.wait();
                eprintln!("[penpot-desktop] Redis stopped.");
            }
        }
        if let Ok(mut g) = state.jvm.lock() {
            if let Some(mut child) = g.take() {
                let _ = child.kill();
                let _ = child.wait();
                eprintln!("[penpot-desktop] JVM backend stopped.");
            }
        }
    }
    let root = project_root(Some(app));
    stop_postgres(&root);
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_fs::init())
        .plugin(tauri_plugin_shell::init())
        .manage(BackendState {
            jvm: Mutex::new(None),
            redis: Mutex::new(None),
        })
        // The desktop app previously had NO invoke_handler — the frontend talked
        // to the JVM backend solely over the same-origin HTTP/WS proxy. This is
        // the first `.invoke_handler`, registering the foundation (F1 `ping`)
        // and the closed AI layer (F4 `llm_*`). Feature 1 appends the `fonts_*`
        // commands here. One `generate_handler!` lists every feature's commands.
        .invoke_handler(tauri::generate_handler![
            commands::ping,
            llm::llm_get_config,
            llm::llm_set_config,
            llm::llm_generate,
            llm::llm_cancel,
            llm::llm_clear_memory,
            llm::llm_agent_step,
            llm::llm_agent_reset,
            llm::llm_agent_progress,
            fonts::fonts_download_family,
            code_export::write_code_zip,
            llm::llm_generate_design_system,
            llm::llm_review_design,
            llm::llm_generate_spec_doc,
            llm::llm_generate_image,
            llm::llm_remove_background,
            llm::llm_upscale_image,
            llm::llm_mcp_status,
            llm::llm_mcp_start,
            llm::llm_mcp_stop,
            llm::llm_mcp_tool_result,
            publish::publish_site,
            publish::submit_form,
            plugin_registry::fetch_plugin_registry,
            cms_import::import_cms_platform,
            stock_assets::stock_search_icons,
            stock_assets::stock_search_photos,
            file_import::import_sketch,
            file_import::import_figma,
            storybook::storybook_fetch,
            team_sharing::post_webhook,
        ])
        .setup(|app| {
            let handle = app.handle().clone();

            // In the packaged build, start the same-origin Rust proxy BEFORE
            // creating the window so http://localhost:1420/loading.html is
            // reachable the instant the window loads. (Dev uses the Node proxy
            // from beforeDevCommand.) The proxy binds synchronously here, then
            // serves static files in a background thread; auto-login is deferred
            // to boot_backend once the JVM is up. Serving the SPA from :1420
            // makes the packaged build same-origin (SPA == public-uri ==
            // rasterizer-uri == API), which is the root-cause fix for the
            // rasterizer postMessage deadlock, template-thumbnail 404s, and
            // cross-origin asset/CORS friction — exactly how dev already works.
            if !cfg!(debug_assertions) {
                let root = project_root(Some(&handle));
                // Feature 1 — the on-demand offline font cache lives under the
                // OS app-data dir (survives upgrades, never in the installer).
                // The proxy's /internal/gfonts routes read/write it.
                let fonts_cache = commands::fonts_cache_dir(&handle)
                    .unwrap_or_else(|_| root.join("data").join("fonts").join("gfonts"));
                proxy::start(
                    root.join("public"),
                    root.join("data").join("assets"),
                    fonts_cache,
                    FRONTEND_PORT,
                );
            }

            // Build the main window IMMEDIATELY at a lightweight loading page
            // so the app is never an invisible background process while the
            // backend boots (30–180s on first run) or if boot fails. boot_backend
            // navigates this same window to the SPA once the backend is ready,
            // or writes a failure message into its status line. Both dev (Node
            // proxy) and release (Rust proxy) load loading.html from :1420, so
            // boot_backend's later location.replace('/') stays same-origin.
            let loading_url = tauri::WebviewUrl::External(
                format!("http://localhost:{}/loading.html", FRONTEND_PORT)
                    .parse()
                    .unwrap(),
            );
            let main_window = tauri::WebviewWindowBuilder::new(&handle, "main", loading_url)
                .title("Ovion Desktop")
                .inner_size(1280.0, 800.0)
                .min_inner_size(900.0, 600.0)
                // Borderless window — the OS titlebar is replaced by the
                // in-app `window-titlebar` component (custom drag region +
                // minimize/maximize/close + theme toggle). See
                // `window_titlebar.cljs` and the `core:window:allow-*`
                // permissions in `capabilities/default.json`.
                .decorations(false)
                .build();

            // DevTools are NOT auto-opened on launch (that was debug scaffolding
            // for the boot/blank-screen issue, now resolved). They remain
            // available on demand via the webview's right-click context menu
            // ("Inspect") in the packaged build because the `tauri` crate is
            // built with the `devtools` feature (see Cargo.toml) — so if
            // something goes wrong the user can still open the Console/Network
            // tabs without a separate inspector build.
            let _ = &main_window;

            std::thread::spawn(move || boot_backend(handle));
            Ok(())
        })
        .on_window_event(|window, event| {
            if matches!(event, tauri::WindowEvent::Destroyed) {
                shutdown_services(&window.app_handle());
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}