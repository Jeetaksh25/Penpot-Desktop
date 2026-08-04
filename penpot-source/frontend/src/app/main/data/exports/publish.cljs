;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.exports.publish
  "Ovion Cloud publish MVP (ALL_APPS_PARITY P0.11) — data layer.

  Builds a static HTML bundle from the current page (the generated CSS +
  HTML body from `app.util.code-gen`, wrapped in a full `<!doctype html>`
  document with SEO `<head>` tags read from the page's `:seo` map) and uploads
  it to Ovion Cloud via the Rust `publish_site` command.

  Two entry points:

    * `build-current-page-bundle` — pure helper that derives the
      `PublishBundle` CLJS map from `state`. Shared by the menu-triggered
      `publish-current-site` event and the `ui.workspace.publish` modal so
      the bundle logic is testable and identical on both paths.

    * `publish-current-site` — a `ptk/WatchEvent` for menu-triggered flows.
      Mirrors `data.workspace.ai-gen/generate-design`: it fires the
      `invoke \"publish_site\"` promise detached and routes the result back
      into the potok store via `publish-succeeded` / `publish-failed`. The
      modal does its own inline invoke for immediate UI feedback and does
      NOT depend on these events.

  Token/endpoint resolution: the frontend never sees the raw Ovion Cloud
  token (`llm_get_config` masks it to a `*_set` bool), so the modal passes
  `nil` and Rust resolves both from `<app-data>/llm.json`. The override
  fields here are kept for completeness / future per-publish keys."
  (:require
   ["@tauri-apps/api/core" :refer [invoke]]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.uuid :as uuid]
   [app.main.data.helpers :as dsh]
   [app.main.store :as st]
   [app.util.code-gen :as cg]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

;; ── Bundle construction ────────────────────────────────────────────────────

(defn- head-seo-tags
  "Emit SEO `<head>` tags from the page's `:seo` map
  `{:title :description :og-image :keywords}`. Any field that is blank/nil is
  omitted. `:keywords` is joined with \", \" when it is a vector."
  [seo]
  (let [title       (:title seo)
        description (:description seo)
        og-image    (:og-image seo)
        keywords    (:keywords seo)
        kw-str      (cond
                      (nil? keywords)     nil
                      (coll? keywords)   (str/join ", " keywords)
                      (str/empty? keywords) nil
                      :else               keywords)]
    (str
     (when (and title (not (str/empty? (str title))))
       (dm/str "<title>" title "</title>\n"))
     (when (and description (not (str/empty? (str description))))
       (dm/str "<meta name=\"description\" content=\""
               (str/replace (str description) "\"" "&quot;") "\">\n"))
     (when (and title (not (str/empty? (str title))))
       (dm/str "<meta property=\"og:title\" content=\""
               (str/replace (str title) "\"" "&quot;") "\">\n"))
     (when (and og-image (not (str/empty? (str og-image))))
       (dm/str "<meta property=\"og:image\" content=\"" og-image "\">\n"))
     (when (and kw-str (not (str/empty? kw-str)))
       (dm/str "<meta name=\"keywords\" content=\""
               (str/replace kw-str "\"" "&quot;") "\">\n")))))

(defn- page-html-document
  "Wrap a page's generated body markup in a full `<!doctype html>` document
  with the CSS inlined in `<head>` and SEO meta tags from `(:seo page)`."
  [page objects css body-markup]
  (let [seo (get page :seo)]
    (dm/str
     "<!doctype html>\n"
     "<html lang=\"en\">\n"
     "<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<style>\n" css "\n</style>\n"
     (head-seo-tags seo)
     "</head>\n"
     "<body>\n" body-markup "\n</body>\n"
     "</html>")))

(defn build-bundle-from-page
  "Derive the Ovion Cloud `PublishBundle` CLJS map from a single `page` (the
  Penpot page map — must carry `:objects`, and optionally `:name` / `:seo`).
  Pure (no `state` dependency) so the modal (which derefs `refs/workspace-page`)
  and the `publish-current-site` store event share identical bundle logic.

  The bundle is multi-page-ready in shape: `pages` is a vector of
  `{slug html}` and `index_html` is the landing document. For MVP only the
  current page is published, so `pages` has one entry whose slug is \"index\"
  and `index_html` is that page's document.

  Root shapes = top-level shapes of the page (`cfh/get-immediate-children`
  under the `uuid/zero` root, excluding invalid geometry); all-shapes = every
  shape in the page (the objects map's values minus the root marker). The
  CSS prelude + generated style code are concatenated, and the markup body is
  `generate-markup-code` for `\"html\"`.

  Returns nil when the page has no top-level shapes (empty page) so callers
  can guard."
  [page]
  (let [objects     (or (:objects page) {})
        root-shapes (cfh/get-immediate-children objects)
        all-shapes  (->> objects
                          vals
                          (remove #(= (:id %) uuid/zero))
                          vec)]
    (when (seq root-shapes)
      (let [css        (dm/str (cg/prelude "css")
                               (cg/generate-style-code objects "css"
                                                       root-shapes all-shapes nil))
            body       (cg/generate-markup-code objects "html" root-shapes)
            html       (page-html-document page objects css body)
            slug       "index"  ;; MVP: single page → index
            index-html html]    ;; single page → index is the page itself
        {:index_html index-html
         :pages       [{:slug slug :html html}]
         :css         css}))))

(defn build-current-page-bundle
  "Derive the `PublishBundle` from the current page in `state`. Delegates to
  `build-bundle-from-page` after resolving the page via `dsh/lookup-page`.
  Returns nil for an empty page."
  [state]
  (build-bundle-from-page (dsh/lookup-page state)))

;; ── Menu-triggered publish event ────────────────────────────────────────────
;;
;; Mirrors `data.workspace.ai-gen/generate-design`: a `ptk/WatchEvent` whose
;; `watch` fires the `invoke \"publish_site\"` promise detached (via
;; `p/then`/`p/catch` — see ai_gen.cljs ~line 646) and routes the result back
;; into the store through `publish-succeeded` / `publish-failed`. The modal
;; does NOT use this path; it owns its own invoke for immediate feedback.

(defn publish-succeeded
  "Event carrying the Ovion Cloud `share-url` returned by `publish_site`.
  Emitted by `publish-current-site` on success."
  [share-url]
  (ptk/reify ::publish-succeeded
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:publish :last-share-url] share-url))))

(defn publish-failed
  "Event carrying the publish error string. Emitted by `publish-current-site`
  on failure."
  [error]
  (ptk/reify ::publish-failed
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:publish :last-error] error))))

(defn publish-current-site
  "Menu-triggered publish. `opts` = `{:token :endpoint}`, both optional
  (Rust resolves them from `llm.json` when blank). Builds the bundle from the
  current page, invokes `publish_site`, and emits `publish-succeeded` /
  `publish-failed` into the store. Empty page → `rx/empty` (no-op)."
  [{:keys [token endpoint]}]
  (ptk/reify ::publish-current-site
    ptk/WatchEvent
    (watch [_ state _]
      (let [bundle (build-current-page-bundle state)]
        (if (nil? bundle)
          (rx/empty)
          (let [request (clj->js {:bundle    bundle
                                  :token     (or token nil)
                                  :endpoint  (or endpoint nil)})
                handle      (fn [result]
                              (let [res        (js->clj result :keywordize-keys true)
                                    share-url  (:share_url res)]
                                (st/emit! (publish-succeeded share-url))))
                handle-err  (fn [err]
                              (st/emit! (publish-failed (str err))))]
            ;; Detached promise — side-effects fire via st/emit! on resolve.
            (-> (invoke "publish_site" #js {:request request})
                (p/then handle)
                (p/catch handle-err))
            (rx/empty)))))))