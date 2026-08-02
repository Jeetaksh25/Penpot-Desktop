// Penpot Desktop — Feature 2 code export: ZIP assembly on the Rust shell.
//
// The frontend builds the full multi-file project tree (per-framework
// `generate-project`) — text files plus binary assets (rasterized PNGs for
// complex shapes, bundled font files) — and either downloads it as a browser
// blob, or persists it to a user-chosen path via this command. The frontend
// opens a native Save-As dialog (`@tauri-apps/plugin-dialog` `save()`) to get
// `out_path`, then `invoke("write_code_zip", { outPath, files })`.
//
// `files` is a flat list of `{ name, content?, binary? }` entries where exactly
// one of `content`/`binary` is set per entry. Nested ZIP paths (`app/page.jsx`,
// `res/drawable/bg.xml`, `assets/icon.png`) are expressed directly in `name` —
// `ZipWriter::start_file` creates intermediate directory entries as needed.

use serde::Deserialize;
use std::fs::File;
use std::io::Write;

use zip::write::SimpleFileOptions;
use zip::CompressionMethod;
use zip::ZipWriter;

/// One entry in the code-export ZIP. Exactly one of `content` (text) or
/// `binary` (raw bytes) must be present. `name` is the in-archive path
/// (forward-slash separated, e.g. `"src/App.jsx"`, `"assets/shape-x.png"`).
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileEntry {
    pub name: String,
    pub content: Option<String>,
    pub binary: Option<Vec<u8>>,
}

/// Write generated code files into a ZIP at `out_path` (an absolute path the
/// frontend obtained from the native Save-As dialog). Returns a human-readable
/// summary. Mirrors `fonts::fonts_download_family`'s `Result<String, String>`
/// error convention (plain `String` errors, `.map_err(|e| format!(…))?`).
#[tauri::command]
pub fn write_code_zip(out_path: String, files: Vec<FileEntry>) -> Result<String, String> {
    let dest = std::path::PathBuf::from(&out_path);
    if let Some(parent) = dest.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("create output dir: {e}"))?;
    }
    let file = File::create(&dest).map_err(|e| format!("create zip file: {e}"))?;
    let mut writer = ZipWriter::new(file);
    let opts = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);

    for entry in &files {
        writer
            .start_file(&entry.name, opts)
            .map_err(|e| format!("start zip entry {}: {e}", entry.name))?;
        match (entry.content.as_ref(), entry.binary.as_ref()) {
            (Some(text), None) => writer
                .write_all(text.as_bytes())
                .map_err(|e| format!("write zip entry {}: {e}", entry.name))?,
            (None, Some(bytes)) => writer
                .write_all(bytes)
                .map_err(|e| format!("write zip entry {}: {e}", entry.name))?,
            (Some(_), Some(_)) => {
                return Err(format!(
                    "entry {} has both content and binary — expected exactly one",
                    entry.name
                ))
            }
            (None, None) => {
                return Err(format!(
                    "entry {} has neither content nor binary",
                    entry.name
                ))
            }
        }
    }

    writer.finish().map_err(|e| format!("finalize zip: {e}"))?;
    Ok(format!(
        "wrote {} file(s) to {}",
        files.len(),
        dest.display()
    ))
}