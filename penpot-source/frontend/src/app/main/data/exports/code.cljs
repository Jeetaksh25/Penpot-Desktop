;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.exports.code
  "Feature 2 code export — assemble a multi-file framework project ZIP on
  the client and persist it to disk.

  Unlike the single-string `generate-framework-code` (the Inspect panel's
  preview path), this builds a runnable project tree via
  `generate-framework-project`: the component plus its scaffold (entry,
  configs, README), bundled @font-face fonts (decoded from the data-URIs the
  Inspect panel already resolves), and — once the native-SVG phase emits
  them — rasterized PNGs for complex shapes.

  Two delivery paths, decided at runtime:

    * Inside Tauri  — open the native Save-As dialog
      (`@tauri-apps/plugin-dialog` `save()`), then `invoke` the Rust
      `write_code_zip` command which assembles the ZIP from the file-entry
      list. This honors the desktop shell's filesystem permissions and is
      the primary path.
    * Web / preview — build the ZIP in-browser with `app.util.zip`
      (`@zip.js/zip.js` BlobWriter) and trigger a browser download. Used as
      a fallback when the dialog plugin / `invoke` is unavailable (e.g. the
      SPA opened in a plain browser) or the native write fails."
  (:require
   ["@tauri-apps/api/core" :refer [invoke]]
   ["@tauri-apps/plugin-dialog" :as tdialog]
   [app.common.data.macros :as dm]
   [app.main.repo :as rp]
   [app.util.code-gen :as cg]
   [app.util.dom :as dom]
   [app.util.http :as http]
   [app.util.zip :as zip]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

;; Web frameworks that consume `:fontfaces-css` (an `@font-face` block in
;; their entry CSS). The mobile/desktop-native frameworks use platform
;; font loading and ignore it, so font binaries are only bundled for these.
(def ^:private web-framework? #{"react" "nextjs" "tailwind"})

(def ^:private default-raster-scale 2)

;; ---------------------------------------------------------------------------
;; @font-face bundling (Phase F)
;; ---------------------------------------------------------------------------

(defn- data-uri? [s]
  (and (string? s) (str/starts-with? (str s) "data:")))

(defn- parse-data-uri
  "Split a `data:<meta>;base64,<b64>` URI into its meta and base64 parts."
  [s]
  (let [without (subs s 5)
        [meta b64] (str/split without "," 2)]
    {:meta (or meta "") :b64 b64}))

(defn- base64->bytes
  "Decode a base64 string into a fresh `Uint8Array`."
  [b64]
  (let [bin (js/atob b64)
        len (.-length bin)
        arr (js/Uint8Array. len)]
    (dotimes [i len]
      (aset arr i (.charCodeAt bin i)))
    arr))

(defn- meta->font-ext [meta]
  (cond
    (str/includes? meta "woff2")      "woff2"
    (str/includes? meta "woff")       "woff"
    (str/includes? meta "truetype")   "ttf"
    (str/includes? meta "opentype")   "otf"
    (str/includes? meta "ttf")        "ttf"
    (str/includes? meta "otf")        "otf"
    :else                             "woff2"))

(defn- derive-font-name
  "A filesystem-safe name for a bundled font, derived from the source URL
  (gstatic hash basenames decode to a stable, unique file name)."
  [url]
  (let [raw (-> url (str/replace #"\?.*$" "") (str/split "/") last)
        clean (-> raw
                  (str/replace #"\.(woff2?|ttf|otf|eot)$" "")
                  (str/replace #"[^a-zA-Z0-9._-]+" "-"))]
    (if (str/blank? clean) "font" clean)))

(defn- bundle-fonts!
  "Decode the font data-URIs into bytes and rewrite the @font-face CSS to
  reference bundled `/fonts/<name>.<ext>` paths. Returns
  `{:css <rewritten-css> :assets <list of {:path :bytes}>}`. Fonts are
  only bundled for web frameworks (React / Next.js / Tailwind); for the
  rest the CSS is returned unchanged and no assets are produced."
  [type fontfaces-css fonts-data]
  (if (or (not (web-framework? type))
          (str/blank? fontfaces-css)
          (empty? fonts-data))
    {:css fontfaces-css :assets []}
    (let [pairs (for [[url data] fonts-data :when (data-uri? data)]
                  (let [{:keys [meta b64]} (parse-data-uri data)
                        bytes (base64->bytes b64)
                        ext (meta->font-ext meta)
                        name (derive-font-name url)]
                    {:url url
                     :path (dm/str "public/fonts/" name "." ext)
                     :rel (dm/str "/fonts/" name "." ext)
                     :bytes bytes}))
          css (reduce (fn [css {:keys [url rel]}]
                        (str/replace css url rel))
                      fontfaces-css pairs)
          assets (mapv #(select-keys % [:path :bytes]) pairs)]
      {:css css :assets assets})))

;; ---------------------------------------------------------------------------
;; PNG raster fallback (Phase D)
;; ---------------------------------------------------------------------------

(defn- export-page-file-ids
  "Resolve `[page-id file-id]` for the backend PNG raster RPC from potok
  state. The workspace keeps the current page on `:current-page-id`; the
  viewer keeps its page on the route's `:query-params`. `:current-file-id`
  is set in both modes."
  [state]
  (let [file-id (:current-file-id state)
        page-id (or (:current-page-id state)
                    (-> state :route :query-params :page-id))]
    [page-id file-id]))

(defn- raster-export-entry
  "Build a backend export entry (the `:exports` payload shape used by
  `rp/cmd! :export` — mirrors the asset-export flow) for one
  raster-request. `objects` provides the original shape; `page-id` /
  `file-id` pin the file/page the backend renders from."
  [state req objects page-id file-id]
  {:enabled   true
   :page-id   page-id
   :file-id   file-id
   :object-id (:shape-id req)
   :shape     (get objects (:shape-id req))
   :name      (:name req)
   :type      :png
   :scale     (:scale req)})

(defn- resolve-one-raster
  "Rasterize a single raster-request to PNG bytes via the backend export
  RPC (`:wait true` returns a single `{:filename :mtype :uri}`), fetch the
  returned URI as a data-URI and decode its base64 payload. Returns a
  promise of `{:binary-path <path> :bytes <Uint8Array>}`. `:binary-path`
  falls back to `assets/<name>.png` when the framework didn't pin one."
  [state req objects page-id file-id]
  (let [profile-id (:profile-id state)
        binary-path (or (:binary-path req) (dm/str "assets/" (:name req) ".png"))
        params {:exports [(raster-export-entry state req objects page-id file-id)]
                :profile-id profile-id
                :cmd :export-shapes
                :wait true
                :is-wasm false}]
    (-> (http/as-promise (rp/cmd! :export params))
        (p/then
         (fn [{:keys [uri]}]
           (if (str/blank? uri)
             (p/rejected (js/Error. (dm/str "[code-export] no export uri for raster " (:name req))))
             (-> (http/as-promise (http/fetch-data-uri uri true))
                 (p/then
                  (fn [uri->data]
                    (let [data-uri (get uri->data uri)
                          {:keys [b64]} (parse-data-uri data-uri)]
                      {:binary-path binary-path
                       :bytes (base64->bytes b64)}))))))))))

(defn- resolve-rasters!
  "Resolve every :raster-request in `project` to a PNG and return a promise
  of `project` with the PNG bytes added to :binary-assets (at each
  request's :binary-path) and :raster-requests cleared. Individual failures
  are warned and dropped so one bad shape never aborts the whole export."
  [state project objects page-id file-id]
  (let [reqs (:raster-requests project)]
    (if (empty? reqs)
      (p/resolved project)
      (-> (p/all
           (for [r reqs]
             (-> (resolve-one-raster state r objects page-id file-id)
                 (p/catch
                  (fn [err]
                    (js/console.warn "[code-export] raster failed for" (:name r) err)
                    nil)))))
          (p/then
           (fn [assets]
             (let [assets (filterv some? assets)]
               (-> project
                   (update :binary-assets #(into (vec %) assets))
                   (assoc :raster-requests [])))))))))

(defn- safe-base-name
  "A filesystem-safe base name for the exported ZIP, derived from the first
  selected shape's name (or 'export'). The `.zip` extension is appended by
  the caller."
  [base-name]
  (let [base (-> (or base-name "export")
                 (str/replace #"[^a-zA-Z0-9\-_]+" "-")
                 (str/replace #"(^-+)|(-+$)" ""))]
    (if (str/blank? base) "export" base)))

(defn- project->entries
  "Flatten a project map into a vector of JS `{name, content? | binary?}`
  entries understood by both the Rust `write_code_zip` command (JSON over
  IPC: `binary` becomes `Vec<u8>` from a number array) and the in-browser
  `app.util.zip` writer (which accepts a `Uint8Array` directly)."
  [project]
  (let [text (for [[path content] (:files project)]
               #js {:name path :content content})
        bin (for [{:keys [path bytes]} (:binary-assets project)]
              #js {:name path :binary (js/Array.from bytes)})]
    (into (vec text) bin)))

(defn- entries->zip-blob
  "Build the ZIP entirely in-browser (the web/preview fallback). Returns a
  `Promise<Blob>` — `zip/close` on a `BlobWriter`-backed `ZipWriter`
  resolves to the finished Blob (mirrors `download-tokens-zip!`)."
  [entries]
  (let [writer (-> (zip/blob-writer {:mtype "application/zip"})
                  (zip/writer))]
    (doseq [e entries]
      (cond
        (some? (.-content e)) (zip/add writer (.-name e) (.-content e))
        (some? (.-binary e)) (zip/add writer (.-name e) (js/Uint8Array.from (.-binary e)))))
    (zip/close writer)))

;; ---------------------------------------------------------------------------
;; Native Save-As + Rust write (primary path inside Tauri)
;; ---------------------------------------------------------------------------

(defn- native-save-and-write!
  "Open the native Save-As dialog and, if the user picks a path, invoke the
  Rust `write_code_zip` command to assemble the ZIP. Resolves to:
    `true`  — written to disk via Rust,
    `false` — the user cancelled the dialog,
  and rejects when the dialog plugin / `invoke` is unavailable (web/preview
  or the IPC failed) so the caller can fall back to a blob download."
  [entries default-name]
  (-> (tdialog/save #js {:default (dm/str default-name ".zip")
                         :filters #js [#js {:name "ZIP archive"
                                            :extensions #js ["zip"]}]})
      (p/then (fn [path]
                (if (str/blank? path)
                  false
                  (-> (invoke "write_code_zip" #js {:outPath path :files entries})
                      (p/then (fn [_] true))))))))

;; ---------------------------------------------------------------------------
;; Public event
;; ---------------------------------------------------------------------------

(defn request-code-project-export
  "Event: build a multi-file framework project ZIP for the current
  selection and save it (native Save-As + `write_code_zip` inside Tauri,
  in-browser blob download otherwise).

  Keys: `:objects` (page objects map, same one the Inspect panel uses),
  `:type` (framework markup-type string), `:shapes` (translated selection
  shapes — only their `:id`s are read), `:fontfaces-css` (the @font-face
  CSS string), `:fonts-data` ({url -> data-uri} map), `:base-name` (used
  for the ZIP file name). The PNG raster RPC's page-id / file-id are
  resolved from potok state (workspace `:current-page-id`, viewer route
  `:query-params`, `:current-file-id` in both)."
  [{:keys [objects type shapes fontfaces-css fonts-data base-name]}]
  (ptk/reify ::request-code-project-export
    ptk/WatchEvent
    (watch [_ state _]
      (let [type (str type)
            {:keys [css font-assets]} (bundle-fonts! type fontfaces-css fonts-data)
            project0 (cg/generate-framework-project objects type shapes
                                                     {:fontfaces-css css
                                                      :scale default-raster-scale})
            project0 (if (seq font-assets)
                       (update project0 :binary-assets #(into (vec %) font-assets))
                       project0)
            ;; Resolve any :raster-requests (complex svg-shapes the native-SVG
            ;; phase deferred to PNG) via the backend export RPC before we
            ;; flatten the project to ZIP entries. No-op when there are none.
            [page-id file-id] (export-page-file-ids state)]
        (-> (resolve-rasters! state project0 objects page-id file-id)
            (p/then
             (fn [project]
               (let [entries (project->entries project)
                     default-name (safe-base-name base-name)]
                 (-> (native-save-and-write! entries default-name)
                     (p/catch
                      (fn [err]
                        (js/console.warn
                         "[code-export] native save unavailable/failed, using blob fallback" err)
                        :no-tauri))
                     (p/then
                      (fn [res]
                        (cond
                          (true? res)       nil           ;; written via Rust
                          (= :no-tauri res) (-> (entries->zip-blob entries)
                                               (p/then (fn [blob]
                                                         (dom/trigger-download
                                                          (dm/str default-name ".zip") blob))))
                          :else             nil)))))))    ;; cancelled in Tauri → no-op
            (p/catch
             (fn [err]
               (js/console.error "[code-export] export failed" err))))
        (->> (rx/from)
             (rx/ignore))))))