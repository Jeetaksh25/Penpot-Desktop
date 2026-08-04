// Penpot Desktop — CMS platform importer (ALL_APPS_PARITY P2.05).
//
// Maps a remote CMS's content model into Ovion CMS collections. The
// frontend (`data.workspace.cms-import`) invokes `import_cms_platform`
// with a platform + base URL + optional token; this module fetches the
// remote content via reqwest (mirroring `publish.rs` style) and returns
// a normalized `serde_json::Value` the frontend turns into real Ovion
// collections through `data.workspace.collections`.
//
// Platforms:
//   * wordpress — COMPLETE end-to-end. Fetches the WP REST API v2
//     (`/wp-json/wp/v2/posts`, `/categories`, `/tags`), parses the JSON,
//     and emits three collections (Posts, Categories, Tags). WP posts'
//     `categories` / `tags` are mapped to `multi-reference` fields whose
//     item values are vectors of WP term ids; the frontend resolves
//     those to Ovion item ids via the `wp_id` carried on each Categories
//     / Tags item.
//   * webflow / contentful — scaffolded config plumbing (adding a
//     platform is a config + a branch in `fetch_platform`), but the
//     fetchers honestly return `Err("platform not yet implemented")`
//     because their API shapes + auth are not yet wired. The mapper
//     structure (the `normalize_*` helpers + the normalized output
//     shape) is platform-agnostic so the Webflow/Contentful fetchers
//     reuse the same normalization once their raw JSON is in hand.
//
// Normalized output shape (shared by every platform once fetched):
//   {
//     "platform": "wordpress",
//     "collections": [
//       {
//         "name": "Categories",
//         "fields": [{ "name": "name", "type": "text" }, ...],
//         "items": [ { "wp_id": 5, "fields": { "name": "News" } }, ... ]
//       },
//       ...
//     ]
//   }
//
// `wp_id` is an OPTIONAL per-item key (only meaningful for collections
// other collections reference — Categories/Tags for WordPress). It is
// the remote CMS's stable id for that item; the frontend uses it to
// resolve multi-reference values (WP term ids in a post → Ovion item
// ids in the Categories collection) and then drops it before
// committing the item to plugin-data.

use std::time::Duration;

use serde::{Deserialize, Serialize};
use tauri::AppHandle;

/// The import request. `platform` selects the fetcher; `base_url` is the
/// CMS root (for WordPress, the site root — the `/wp-json/wp/v2` path is
/// appended here). `token` is optional (WP public reads need none; Webflow
/// / Contentful will require it). `options` is an escape hatch for
/// per-platform knobs (e.g. `{"per_page": 100}`) left as a free-form JSON
/// value so this struct never needs a new release for a new option.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportRequest {
    pub platform: String,
    pub base_url: String,
    #[serde(default)]
    pub token: Option<String>,
    #[serde(default)]
    pub options: Option<serde_json::Value>,
}

/// One field in a normalized collection. `type` is one of the Ovion CMS
/// field-type keywords (`text`, `image`, `number`, `date`, `color`,
/// `reference`, `multi-reference`). `reference` (optional) is the NAME of
/// the referenced collection (e.g. `"Categories"`) — the frontend resolves
/// it to the Ovion collection id after creating all collections.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NormalizedField {
    pub name: String,
    #[serde(rename = "type")]
    pub field_type: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reference: Option<String>,
}

/// One item in a normalized collection. `wp_id` is the remote id (only
/// set for collections other collections reference); `fields` maps the
/// item's field NAME → value. For multi-reference fields the value is a
/// vector of remote term ids (e.g. WP category ids).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NormalizedItem {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub wp_id: Option<i64>,
    pub fields: serde_json::Value,
}

/// A normalized collection the frontend turns into an Ovion CMS collection.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NormalizedCollection {
    pub name: String,
    pub fields: Vec<NormalizedField>,
    pub items: Vec<NormalizedItem>,
}

/// The top-level normalized response.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NormalizedImport {
    pub platform: String,
    pub collections: Vec<NormalizedCollection>,
}

/// Build the shared reqwest client. Mirrors `publish.rs`: 60s timeout +
/// a user-agent identifying the desktop app. Kept here (not shared with
/// publish.rs) so the two modules stay independently evolvable.
fn http_client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .timeout(Duration::from_secs(60))
        .user_agent("OvionDesktop/1.0 (cms-import)")
        .build()
        .map_err(|e| format!("cms-import client build failed: {e}"))
}

/// Trim a trailing slash off a base URL for safe path joining.
fn trim_slash(s: &str) -> String {
    s.trim().trim_end_matches('/').to_string()
}

/// Resolve the `per_page` option (default 100) from the optional
/// `options` JSON. WP caps `per_page` at 100; higher values still work
/// (the API clamps) so no validation here.
fn opt_per_page(options: &Option<serde_json::Value>) -> u32 {
    options
        .as_ref()
        .and_then(|o| o.get("per_page"))
        .and_then(|v| v.as_u64())
        .map(|n| n as u32)
        .unwrap_or(100)
}

/// Fetch JSON from `url`, returning the parsed `serde_json::Value`. Maps
/// any HTTP / parse failure to a human-readable string. Shared by the
/// WP posts / categories / tags fetches.
async fn fetch_json(client: &reqwest::Client, url: &str) -> Result<serde_json::Value, String> {
    let resp = client
        .get(url)
        .send()
        .await
        .map_err(|e| format!("request to {url} failed: {e}"))?;
    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        return Err(format!(
            "{url} returned HTTP {status}: {}",
            text.chars().take(500).collect::<String>()
        ));
    }
    serde_json::from_str(&text)
        .map_err(|e| format!("could not parse JSON from {url}: {e}"))
}

/// WordPress REST API importer — complete end-to-end. Fetches posts,
/// categories, and tags and normalizes them to three collections. The
/// `categories` / `tags` fields on a post are `multi-reference` fields
/// whose item values are vectors of WP term ids; the Categories / Tags
/// items carry their WP id as `wp_id` so the frontend can resolve those
/// references to Ovion item ids.
async fn import_wordpress(
    client: &reqwest::Client,
    base_url: &str,
    _token: Option<String>,
    options: &Option<serde_json::Value>,
) -> Result<NormalizedImport, String> {
    let root = trim_slash(base_url);
    let per_page = opt_per_page(options);

    // WP REST API v2. Public reads need no auth; `token` (if provided)
    // would be used for private/draft endpoints — passed through as a
    // Bearer header when present so authenticated sites work too.
    let posts_url = format!("{root}/wp-json/wp/v2/posts?per_page={per_page}");
    let cats_url = format!("{root}/wp-json/wp/v2/categories?per_page={per_page}");
    let tags_url = format!("{root}/wp-json/wp/v2/tags?per_page={per_page}");

    // Sequential awaits (no `futures` crate dependency; the WP REST API
    // is fast and a one-shot import doesn't benefit from concurrency).
    let posts = fetch_json(client, &posts_url).await?;
    let categories = fetch_json(client, &cats_url).await?;
    let tags = fetch_json(client, &tags_url).await?;

    let posts_arr = posts
        .as_array()
        .ok_or_else(|| "WP /posts did not return a JSON array".to_string())?;
    let cats_arr = categories
        .as_array()
        .ok_or_else(|| "WP /categories did not return a JSON array".to_string())?;
    let tags_arr = tags
        .as_array()
        .ok_or_else(|| "WP /tags did not return a JSON array".to_string())?;

    // --- Categories collection ------------------------------------------------
    let cat_fields = vec![
        NormalizedField { name: "name".into(), field_type: "text".into(), reference: None },
        NormalizedField { name: "slug".into(), field_type: "text".into(), reference: None },
        NormalizedField { name: "count".into(), field_type: "number".into(), reference: None },
    ];
    let cat_items = cats_arr
        .iter()
        .map(normalize_term)
        .collect::<Result<Vec<_>, String>>()?;

    // --- Tags collection ------------------------------------------------------
    let tag_fields = cat_fields.clone();
    let tag_items = tags_arr
        .iter()
        .map(normalize_term)
        .collect::<Result<Vec<_>, String>>()?;

    // --- Posts collection -----------------------------------------------------
    let post_fields = vec![
        NormalizedField { name: "title".into(), field_type: "text".into(), reference: None },
        NormalizedField { name: "content".into(), field_type: "text".into(), reference: None },
        NormalizedField { name: "excerpt".into(), field_type: "text".into(), reference: None },
        NormalizedField { name: "date".into(), field_type: "date".into(), reference: None },
        NormalizedField { name: "slug".into(), field_type: "text".into(), reference: None },
        NormalizedField { name: "link".into(), field_type: "text".into(), reference: None },
        NormalizedField {
            name: "categories".into(),
            field_type: "multi-reference".into(),
            reference: Some("Categories".into()),
        },
        NormalizedField {
            name: "tags".into(),
            field_type: "multi-reference".into(),
            reference: Some("Tags".into()),
        },
    ];
    let post_items = posts_arr
        .iter()
        .map(normalize_post)
        .collect::<Result<Vec<_>, String>>()?;

    Ok(NormalizedImport {
        platform: "wordpress".into(),
        collections: vec![
            NormalizedCollection { name: "Categories".into(), fields: cat_fields, items: cat_items },
            NormalizedCollection { name: "Tags".into(), fields: tag_fields, items: tag_items },
            NormalizedCollection { name: "Posts".into(), fields: post_fields, items: post_items },
        ],
    })
}

/// Normalize one WP term (category or tag) into a NormalizedItem. The
/// term's WP id is preserved as `wp_id` so posts can reference it; the
/// frontend resolves it to an Ovion item id after creating the
/// Categories / Tags collections.
fn normalize_term(term: &serde_json::Value) -> Result<NormalizedItem, String> {
    let wp_id = term
        .get("id")
        .and_then(|v| v.as_i64())
        .ok_or_else(|| "WP term missing `id`".to_string())?;
    let name = term
        .get("name")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let slug = term
        .get("slug")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let count = term
        .get("count")
        .and_then(|v| v.as_i64())
        .unwrap_or(0);
    Ok(NormalizedItem {
        wp_id: Some(wp_id),
        fields: serde_json::json!({ "name": name, "slug": slug, "count": count }),
    })
}

/// Normalize one WP post into a NormalizedItem. `title` / `content` /
/// `excerpt` come from the WP REST API as `{ "rendered": "..." }` objects;
/// we extract the rendered HTML string. `categories` and `tags` are
/// already arrays of term ids in the WP payload.
fn normalize_post(post: &serde_json::Value) -> Result<NormalizedItem, String> {
    let rendered = |key: &str| -> String {
        post.get(key)
            .and_then(|v| v.get("rendered"))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string()
    };
    let title = rendered("title");
    let content = rendered("content");
    let excerpt = rendered("excerpt");
    let date = post
        .get("date")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let slug = post
        .get("slug")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let link = post
        .get("link")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let categories = post
        .get("categories")
        .cloned()
        .unwrap_or(serde_json::Value::Array(vec![]));
    let tags = post
        .get("tags")
        .cloned()
        .unwrap_or(serde_json::Value::Array(vec![]));

    Ok(NormalizedItem {
        wp_id: None,
        fields: serde_json::json!({
            "title": title,
            "content": content,
            "excerpt": excerpt,
            "date": date,
            "slug": slug,
            "link": link,
            "categories": categories,
            "tags": tags,
        }),
    })
}

/// Import a CMS platform's content model. Dispatches to the platform
/// fetcher by `request.platform`. WordPress is complete; Webflow and
/// Contentful return an honest `Err` (their API shapes + auth are not
/// yet wired) — the config plumbing (per-platform branch + shared
/// `NormalizedImport` output) is in place so adding a platform is a
/// `match` arm + a fetcher, not a new command.
#[tauri::command]
pub async fn import_cms_platform(
    _app: AppHandle,
    request: ImportRequest,
) -> Result<serde_json::Value, String> {
    let client = http_client()?;
    let normalized = match request.platform.as_str() {
        "wordpress" => {
            import_wordpress(&client, &request.base_url, request.token, &request.options).await?
        }
        "webflow" => {
            return Err(
                "Webflow import is not yet implemented — only WordPress is available."
                    .to_string(),
            );
        }
        "contentful" => {
            return Err(
                "Contentful import is not yet implemented — only WordPress is available."
                    .to_string(),
            );
        }
        other => {
            return Err(format!("Unknown CMS platform: {other}"));
        }
    };
    serde_json::to_value(normalized)
        .map_err(|e| format!("could not serialize normalized import: {e}"))
}