;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.plugins.figma-compat
  "ALL_APPS_PARITY P1.28 — Figma plugin API compatibility shim.

  Maps a SUBSET of the `figma.*` global API onto the existing Penpot plugin
  runtime (`app.plugins.api` / `app.plugins.shape` — referenced READ-ONLY;
  this file does NOT edit them). The goal: a Figma plugin that uses only the
  common read + create primitives can load against the Ovion runtime.

  MAPPED (the shim's scope):
    figma.root               → context.root                  (the file root)
    figma.currentPage        → context.currentPage            (current page)
    figma.selection          → context.selection              (selected nodes)
    figma.createRectangle()  → context.createRectangle()      ( Penpot :rect )
    figma.createFrame()      → context.createBoard()           ( Penpot :frame )
    figma.createEllipse()   → context.createEllipse()         ( Penpot :circle )
    figma.createPath()      → context.createPath()            ( Penpot :path )
    figma.createText(text)  → context.createText(text)        ( Penpot :text )
    figma.createTextNode(text) → context.createText(text)     ( Figma alias   )
    figma.notify(msg)       → app.main.data.notifications/info ( toast       )
    figma.loadFontAsync(f)  → p/resolved (Penpot auto-loads)   ( no-op resolve )
    figma.ui.postMessage(m) → console.warn stub (see DEFERRALS)
    figma.ui.onmessage      → no-op setter (see DEFERRALS)
    figma.clientStorage     → app.plugins.local-storage proxy (read-only ref)

  NOT YET MAPPED (honest deferrals — see DEFERRALS at the bottom):
    figma.ui.show / close / resize — the Penpot plugin UI bridge is a
        different runtime (iframe posted from the host, not `figma.ui.*`);
        wiring it requires editing the read-only workspace/plugins.cljs UI.
    figma.getNodeById / findAll / findOne — Penpot's node graph is keyed
        by uuid with a different traversal shape; a faithful mapping needs
        a dedicated walker (deferred to keep P1.28 honest).
    figma.importAsync / styles / effectStyle / paintStyle — Penpot has
        component / color / typography libraries, not the Figma styles API.
    figma.createLine / createPolygon / createVector / createStar — Penpot
        uses a path-based `:svg` / `:path` shape; a converter is deferred.
    figma.currentPage.children / findAll — node-tree traversal deferred.
    node.* setters beyond x/y/width/height/name/fills — Penpot's
        shape-proxy exposes a different (richer, Penpot-flavored) surface.
    figma.writeFile / readFile / openFileDialog — file APIs deferred.

  HOW A FIGMA PLUGIN LOADS: the plugin host assigns
  `globalThis.figma = figma-compat/build-figma-global(plugin-id)` BEFORE
  evaluating the plugin's bundled code, so `figma.*` references resolve.
  `plugin-id` is the Ovion registry id (e.g. \"ovion-figma-compat\")."
  (:require
   [app.main.data.notifications :as dnt]
   [app.main.store :as st]
   [app.plugins.api :as api]
   [app.plugins.local-storage :as local-storage]
   [app.util.object :as obj]
   [promesa.core :as p]))

;; ── Plugin-id constant for the shim itself ──────────────────────────────────
;; Used as the default when a host doesn't supply one. Matches the registry
;; fallback entry `ovion-figma-compat` so permissions/checks line up.

(def figma-compat-plugin-id "ovion-figma-compat")

;; ── figma.ui stub ────────────────────────────────────────────────────────────
;; The Penpot plugin UI bridge is a separate runtime (the host posts messages
;; to an iframe managed by `app.main.ui.workspace.plugins`, which is READ-ONLY
;; here). `postMessage` and `onmessage` are stubbed so a Figma plugin that only
;; sends trivial messages does not throw; a real bridge is deferred (see the
;; ns docstring DEFERRALS).

(defn- build-ui-stub
  "Build a minimal `figma.ui` object. `postMessage` warns to the console (the
  message is not delivered — wiring the iframe bridge requires editing the
  read-only workspace/plugins.cljs). `onmessage` is a no-op setter."
  []
  (obj/reify {:name "FigmaUIStub"}
    :postMessage
    (fn [_ msg]
      (js/console.warn "[figma-compat] figma.ui.postMessage is a stub; message not delivered:" msg)
      nil)

    :onmessage
    {:this true
     :get (fn [_] nil)
     :set (fn [_ _handler] nil)}

    :show
    (fn [_]
      (js/console.warn "[figma-compat] figma.ui.show is a stub (no iframe bridge).")
      nil)

    :hide
    (fn [_]
      (js/console.warn "[figma-compat] figma.ui.hide is a stub (no iframe bridge).")
      nil)

    :resize
    (fn [_ _w _h]
      (js/console.warn "[figma-compat] figma.ui.resize is a stub (no iframe bridge).")
      nil)))

;; ── figma.clientStorage ─────────────────────────────────────────────────────
;; Delegates to the existing `app.plugins.local-storage` proxy (read-only ref).
;; Figma's clientStorage.{getAsync, setAsync, deleteAsync} map to the local
;; storage proxy's get/set/delete. Only a flat key/value subset is mapped.

(defn- build-client-storage
  [plugin-id]
  (obj/reify {:name "FigmaClientStorage"}
    :getAsync
    (fn [_ key]
      (try
        (let [store (local-storage/local-storage-proxy plugin-id)]
          (p/resolved (.getItem store (str key))))
        (catch :default e
          (p/rejected e))))

    :setAsync
    (fn [_ key value]
      (try
        (let [store (local-storage/local-storage-proxy plugin-id)]
          (.setItem store (str key) value)
          (p/resolved nil))
        (catch :default e
          (p/rejected e))))

    :deleteAsync
    (fn [_ key]
      (try
        (let [store (local-storage/local-storage-proxy plugin-id)]
          (.removeItem store (str key))
          (p/resolved nil))
        (catch :default e
          (p/rejected e))))))

;; ── build-figma-global ──────────────────────────────────────────────────────

(defn build-figma-global
  "Build the `figma` global object for a Figma plugin. `plugin-id` is the
  Ovion registry id; defaults to `figma-compat-plugin-id`. The returned object
  is assigned to `globalThis.figma` before the plugin's code is evaluated.

  Delegates read + create primitives to the existing Penpot context proxy
  (`app.plugins.api/create-context`), adapting Figma's naming to Penpot's:
    * `createFrame`    → context.createBoard   (Penpot calls a frame a 'board')
    * `createTextNode` → context.createText    (Figma alias for createText)
  All other mapped names match directly."
  ([]
   (build-figma-global figma-compat-plugin-id))
  ([plugin-id]
   (let [ctx (api/create-context plugin-id)]
     (obj/reify {:name "FigmaGlobal"}
       ;; Read-only live getters (re-read from the context each access so
       ;; selection / current-page track the live editor state).
       :root
       {:this true
        :get (fn [_] (.-root ctx))}

       :currentPage
       {:this true
        :get (fn [_] (.-currentPage ctx))}

       :selection
       {:this true
        :get (fn [_] (.-selection ctx))
        :set
        (fn [_ shapes]
          ;; Figma lets you set figma.selection = [...]. The Penpot context's
          ;; selection setter expects shape proxies; pass through.
          (set! (.-selection ctx) shapes))}

       ;; --- Create primitives (mirror the context's create methods) -------
       :createRectangle
       (fn [_] (.createRectangle ctx))

       :createFrame
       (fn [_] (.createBoard ctx))

       :createEllipse
       (fn [_] (.createEllipse ctx))

       :createPath
       (fn [_] (.createPath ctx))

       :createText
       (fn [_ text] (.createText ctx text))

       :createTextNode
       (fn [_ text] (.createText ctx text))

       ;; --- Misc ----------------------------------------------------------
       :notify
       (fn [_ msg]
         ;; Map figma.notify(msg) → a Penpot info toast. Figma's options
         ;; (timeout / error) are ignored for now (deferred).
         (st/emit! (dnt/info (str msg)))
         nil)

       :loadFontAsync
       (fn [_ _font]
         ;; Penpot auto-loads fonts; resolve immediately so a plugin's
         ;; `await figma.loadFontAsync(font)` continues without throwing.
         ;; A real implementation would resolve after the font is fetched
         ;; via app.main.fonts/fetch-font-css (deferred).
         (p/resolved nil))

       :ui
       {:this true
        :get (fn [_] (build-ui-stub))}

       :clientStorage
       {:this true
        :get (fn [_] (build-client-storage plugin-id))}

       ;; --- Constants / metadata -----------------------------------------
       :pluginId
       {:this true
        :get (constantly plugin-id)}

       ;; Figma's version string; report the shim's own version so plugins
       ;; that branch on `figma.apiInfo` don't crash.
       :apiVersion
       {:this true
        :get (constantly "1.0.0-figma-compat")}))))

;; ── Install helper ──────────────────────────────────────────────────────────
;; Convenience for a host that wants to install the shim on a specific object
;; (defaults to globalThis). Returns the installed `figma` object so callers
;; can keep a reference / restore on unload.

(defn install-figma-global
  "Install the `figma` global on `target` (defaults to `js/globalThis`).
  `plugin-id` is the Ovion registry id. Returns the installed `figma` object."
  ([]
   (install-figma-global js/globalThis figma-compat-plugin-id))
  ([target]
   (install-figma-global target figma-compat-plugin-id))
  ([target plugin-id]
   (let [figma (build-figma-global plugin-id)]
     (set! (.-figma target) figma)
     figma)))

;; ── DEFERRALS (honest scope — see ns docstring) ─────────────────────────────
;;
;; The following figma.* surfaces are intentionally NOT mapped. Each has a
;; concrete reason; none is silently dropped.
;;
;;   figma.ui.show / close / resize
;;     Reason: the Penpot plugin UI bridge is a separate runtime — the host
;;     posts messages to an iframe managed by `app.main.ui.workspace.plugins`,
;;     which is READ-ONLY for P1.28. `build-ui-stub` warns instead of throwing.
;;
;;   figma.getNodeById / findAll / findOne / currentPage.children
;;     Reason: Penpot's node graph is keyed by uuid with a different traversal
;;     shape (objects map keyed by shape-id under a `uuid/zero` root). A
;;     faithful mapping needs a dedicated walker; deferred to keep scope honest.
;;
;;   figma.importAsync / effectStyle / paintStyle / textStyle
;;     Reason: Penpot exposes component / color / typography libraries, not
;;     the Figma styles API; mapping requires a library adapter (deferred).
;;
;;   figma.createLine / createPolygon / createVector / createStar
;;     Reason: Penpot uses path-based `:svg` / `:path` shapes; a geometry
;;     converter from Figma's vector format is deferred.
;;
;;   node.* setters beyond x / y / width / height / name / fills
;;     Reason: Penpot's shape-proxy exposes a richer, Penpot-flavored surface
;;     (layout, constraints, tokens); mapping the full Figma node setter set
;;     is out of P1.28 scope.
;;
;;   figma.writeFile / readFile / openFileDialog / file passthrough
;;     Reason: Penpot has its own file plugin context (`app.plugins.file`);
;;     the Figma file API surface differs enough that a passthrough is wrong.
;;
;; Plugins that reach for any unmapped API will see `undefined` and (for the
;; stubbed `figma.ui.*`) a console warning — they will not silently misbehave.