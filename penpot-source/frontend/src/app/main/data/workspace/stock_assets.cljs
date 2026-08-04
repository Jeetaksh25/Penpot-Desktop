;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.stock-assets
  "P0.04 — Built-in stock asset library data layer.

  Thin invoke wrappers around the Rust `stock_search_icons` /
  `stock_search_photos` Tauri commands (see `src-tauri/src/stock_assets.rs`).
  Mirrors the established `data.exports.publish` / `data.workspace.ai-gen`
  pattern: raw `invoke \"…\"` returning a promesa promise; the caller (the
  Stock section UI in `ui.workspace.sidebar.assets`) keywordizes with
  `js->clj` and renders.

  The Rust side already keeps a session-scoped in-process cache keyed by
  query (+ page for photos) for offline reuse within the session. This
  layer adds nothing more on the CLJS side — the cache lives in Rust so it
  survives CLJS re-renders and is shared across all callers. We only keep
  the last successful result per category here so a re-render after a
  background store update doesn't re-fetch.

  Pexels key gating: the Pexels API key never lives in the Rust config
  (it is a stock-asset concern, not an AI concern). It is held in browser
  localStorage under `ovion.pexels-key` and passed to
  `stock_search_photos` on every call. When absent/empty the Rust command
  returns the sentinel `\"pexels-key-missing\"` which the UI surfaces as a
  'set your Pexels key' empty state (no network call is made). This keeps
  the key user-supplied + revocable + never persisted server-side, and
  means the photos tab degrades gracefully to an icons-only experience
  until the user opts in."
  (:require
   ["@tauri-apps/api/core" :refer [invoke]]
   [app.common.data.macros :as dm]
   [cuerdas.core :as str]
   [promesa.core :as p]))

;; ── Pexels key (browser localStorage, user-supplied) ─────────────────────────

(def ^:private pexels-key-storage-key "ovion.pexels-key")

(defn load-pexels-key
  "Read the saved Pexels API key from localStorage. Returns a string (empty
  when unset or unavailable — the photos tab then shows the key-missing
  empty state). Nil-safe against browsers that throw on localStorage
  access (private mode)."
  []
  (try
    (or (.getItem js/localStorage pexels-key-storage-key) "")
    (catch :default _ "")))

(defn save-pexels-key
  "Persist `key` to localStorage and return it. Empty string clears the
  key (re-enables the key-missing empty state on the photos tab)."
  [key]
  (try
    (if (str/blank? key)
      (.removeItem js/localStorage pexels-key-storage-key)
      (.setItem js/localStorage pexels-key-storage-key key))
    (catch :default _))
  key)

(defn pexels-key-set?
  "True when a non-blank Pexels key is saved in localStorage."
  []
  (not (str/blank? (load-pexels-key))))

;; ── Invoke wrappers ──────────────────────────────────────────────────────────

(defn search-icons
  "Search Iconify by keyword. Returns a promesa promise resolving to a JS
  object `{icons, total, cached}` where `icons` is an array of
  `{name, body, width, height}`. On error the promise rejects with the
  Rust error string; callers should catch and render an error state.

  `limit` defaults to 64 on the Rust side when nil."
  ([query]
   (search-icons query nil))
  ([query limit]
   (invoke "stock_search_icons" #js {:query query :limit limit})))

(defn search-photos
  "Search Pexels by keyword. Returns a promesa promise resolving to the
  raw Pexels JS object `{photos, total_results, page, per_page, cached}`.
  Rejects with the sentinel `\"pexels-key-missing\"` when no key is set,
  or `\"pexels-key-invalid\"` on HTTP 401/403 — callers match these
  exactly to render the right empty state.

  `page` defaults to 1 on the Rust side when nil."
  ([query]
   (search-photos query (load-pexels-key) nil))
  ([query pexels-key page]
   (invoke "stock_search_photos"
           #js {:query query :pexelsKey pexels-key :page page})))

;; ── Local last-result cache (re-render guard only) ───────────────────────────
;;
;; The Rust command is the source of truth for the offline cache; this
;; atom only memoizes the last rendered result per category so a parent
;; re-render (e.g. dev-tools ref churn) does not force a re-fetch. Keys:
;; `:icons/<query>` and `:photos/<query>/<page>`.

(defonce ^:private last-result
  (atom {}))

(defn cached-icons
  "Return the last successfully fetched icon result for `query`, or nil."
  [query]
  (get @last-result [:icons query]))

(defn remember-icons!
  [query result]
  (swap! last-result assoc [:icons query] result)
  result)

(defn cached-photos
  "Return the last successfully fetched photo result for `query`/`page`,
  or nil."
  [query page]
  (get @last-result [:photos query page]))

(defn remember-photos!
  [query page result]
  (swap! last-result assoc [:photos query page] result)
  result)

(defn clear-cache!
  "Drop all locally memoized stock results (e.g. when the user clears the
  search). The Rust session cache is not affected."
  []
  (reset! last-result {}))