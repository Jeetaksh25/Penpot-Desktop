;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.plugin-center
  "ALL_APPS_PARITY P1.28 — Plugin Center sidebar menu.

  A browsable plugin marketplace panel backed by the Ovion-hosted plugin
  registry (fetched by the Rust `fetch_plugin_registry` command and routed
  through `data.workspace.plugin-registry`). Renders:

    * a header row with a refresh button,
    * a search box (filters by name / author / description),
    * a category filter (All + the categories present in the registry),
    * a row per plugin (icon, name, author, description, install/enable
      toggle).

  'Install' adds the plugin to the profile registry via the existing
  `app.plugins.register` (READ-only reference — no edit) AND marks it enabled
  in the current file's plugin-data slot. 'Enable' flips the per-file flag.
  The actual plugin runtime loading path is the existing Penpot plugin
  runtime (`app.main.data.plugins` / `app.plugins.api`) — this panel only
  manages registry membership, it does not load plugin code itself.

  Lucide icons are inlined (viewBox 0 0 24 24, stroke-width 2, currentColor).
  Coral accent #f28b82 for active toggles; grey #7d7d7d for secondary text.
  i18n keys are placeholders — the lead adds them to en.po."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.workspace.plugin-registry :as dpc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.util.i18n :as i18n :refer [tr]]
   [clojure.string :as cstr]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; ── Derived refs ────────────────────────────────────────────────────────────
;; The registry envelope lives at `[:plugin-center :registry]` (set by
;; `data.workspace.plugin-registry/registry-loaded`). The per-file enabled
;; map lives on the page's plugin-data slot (read by `dpc/read-enabled-map`).
;; Both are derived here so the shared refs.cljs stays untouched.

(def registry-envelope
  (l/derived (fn [state]
               (get-in state [:plugin-center :registry]))
             st/state =))

(def registry-status
  (l/derived (fn [state]
               (get-in state [:plugin-center :status]))
             st/state =))

(def enabled-map
  (l/derived (fn [page]
               (dpc/read-enabled-map page))
             refs/workspace-page =))

;; ── Inline Lucide icons (viewBox 0 0 24 24, stroke-width 2, currentColor) ──

(defn- lucide-icon
  "Render an inline Lucide-style SVG icon. `children` is a vector of Hiccup
  children. Uses currentColor so the icon inherits text color."
  [children]
  [:svg {:viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :stroke-width 2
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :width 16
         :height 16
         :style {:flex-shrink 0}}
   children])

(defn- icon-plus []     (lucide-icon [[:path {:d "M12 5v14"}] [:path {:d "M5 12h14"}]]))
(defn- icon-chevron []  (lucide-icon [[:path {:d "m6 9 6 6 6-6"}]]))
(defn- icon-refresh []  (lucide-icon [[:path {:d "M3 12a9 9 0 0 1 15-6.7L21 8"}]
                                       [:path {:d "M21 3v5h-5"}]
                                       [:path {:d "M21 12a9 9 0 0 1-15 6.7L3 16"}]
                                       [:path {:d "M3 21v-5h5"}]]))
(defn- icon-search []   (lucide-icon [[:circle {:cx 11 :cy 11 :r 7}]
                                       [:path {:d "m21 21-4.3-4.3"}]]))
(defn- icon-puzzle []   (lucide-icon [[:path {:d "M19.439 7.85c-.049.322.059.648.289.878l1.568 1.568c.47.47.706 1.087.706 1.704s-.235 1.233-.706 1.704l-1.611 1.611a.98.98 0 0 1-.837.276c-.47-.07-.802-.48-.968-.925a2.501 2.501 0 1 0-3.214 3.214c.446.166.855.497.925.968a.98.98 0 0 1-.276.837l-1.61 1.61a2.404 2.404 0 0 1-1.705.707 2.402 2.402 0 0 1-1.704-.706l-1.568-1.568a1.026 1.026 0 0 0-.877-.29c-.493.074-.84.504-1.02.968a2.5 2.5 0 1 0-2.96-3.213c.165.464.501.894.968.968a1.026 1.026 0 0 1-.29.877l-1.568 1.568A2.402 2.402 0 0 1 1.998 19.4a2.4 2.4 0 0 1 .706-1.704l1.568-1.568c.23-.23.556-.338.878-.29.463.07.894.406.968.968a2.5 2.5 0 1 0 3.213-2.96c-.464-.165-.894-.526-.968-.968a1.026 1.026 0 0 1 .29-.878l1.611-1.611A2.402 2.402 0 0 1 12 5.594c.617 0 1.234.236 1.704.706l1.568 1.568c.23.23.556.338.878.29.463-.07.894-.406.968-.968a2.5 2.5 0 1 1 3.213 2.96c-.165.464-.501.894-.968.968Z"}]]))
(defn- icon-download [] (lucide-icon [[:path {:d "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"}]
                                       [:path {:d "M7 10l5 5 5-5"}]
                                       [:path {:d "M12 15V3"}]]))
(defn- icon-shield []   (lucide-icon [[:path {:d "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z"}]
                                       [:path {:d "m9 12 2 2 4-4"}]]))

(def ^:private icon-by-name
  {"puzzle"       icon-puzzle
   "download"     icon-download
   "shield-check" icon-shield})

(defn- plugin-icon
  "Resolve the icon function for a plugin entry by `name`, falling back to the
  puzzle icon when the name is unknown. Returns the icon component function
  (rendered by the caller via `[:& ...]`)."
  [name]
  (get icon-by-name name icon-puzzle))

;; ── Category filter ─────────────────────────────────────────────────────────

(defn- categories-from-registry
  "Collect the distinct `:category` values across `plugins`, keeping
  first-seen order. Unknown/nil categories collapse to \"other\"."
  [plugins]
  (->> plugins
       (map #(or (:category %) "other"))
       (distinct)
       (sort)
       vec))

(defn- plugin-matches?
  "True when `plugin` matches the current `query` (case-insensitive substring
  against name/author/description) and `category` filter."
  [plugin query category]
  (let [name        (or (:name plugin) "")
        author      (or (:author plugin) "")
        description (or (:description plugin) "")
        cat         (or (:category plugin) "other")
        hay         (str (cstr/lower-case name) " "
                        (cstr/lower-case author) " "
                        (cstr/lower-case description))]
    (and (or (cstr/blank? query)
             (cstr/includes? hay (cstr/lower-case query)))
         (or (= category "all")
             (= category cat)))))

;; ── Plugin row ──────────────────────────────────────────────────────────────

(mf/defc plugin-row*
  {::mf/wrap [mf/memo]}
  [{:keys [plugin installed? enabled?]}]
  (let [pid          (:id plugin)
        on-install   (mf/use-fn
                      (mf/deps pid)
                      (fn [_]
                        (st/emit! (dpc/install-plugin {:plugin plugin}))))
        on-uninstall (mf/use-fn
                      (mf/deps pid)
                      (fn [_]
                        (st/emit! (dpc/uninstall-plugin {:plugin plugin}))))
        on-toggle-enabled
        (mf/use-fn
         (mf/deps pid enabled?)
         (fn [_]
           (if enabled?
             (st/emit! (dpc/disable-plugin {:plugin plugin}))
             (st/emit! (dpc/enable-plugin {:plugin plugin})))))]

    [:div {:class (stl/css :pc-row)}
     [:div {:class (stl/css :pc-row-icon)}
      [:& (plugin-icon (:icon plugin))]]
     [:div {:class (stl/css :pc-row-info)}
      [:div {:class (stl/css :pc-row-title)}
       [:span {:class (stl/css :pc-row-name)} (or (:name plugin) (tr "workspace.options.plugin-center.untitled"))]
       [:span {:class (stl/css :pc-row-author)} (or (:author plugin) "")]]
      [:div {:class (stl/css :pc-row-desc)} (or (:description plugin) "")]
      [:div {:class (stl/css :pc-row-meta)}
       (when (:version plugin)
         [:span {:class (stl/css :pc-row-version)}
          (dm/str "v" (:version plugin))])
       (when (:category plugin)
         [:span {:class (stl/css :pc-row-category)} (:category plugin)])]]
     [:div {:class (stl/css :pc-row-actions)}
      (if (not installed?)
        ;; Not installed → single Install button.
        [:button {:type      "button"
                  :class     (stl/css :pc-install-btn)
                  :on-click  on-install}
         (tr "workspace.options.plugin-center.install")]
        ;; Installed → enable toggle + uninstall.
        [:<>
         [:button {:type        "button"
                   :class       (stl/css-case :pc-toggle true
                                              :pc-toggle-active (boolean enabled?))
                   :aria-pressed (boolean enabled?)
                   :aria-label  (tr "workspace.options.plugin-center.toggle-enabled")
                   :title       (tr "workspace.options.plugin-center.toggle-enabled")
                   :on-click    on-toggle-enabled}
          [:span {:class (stl/css :pc-toggle-dot)}]]
         [:button {:type      "button"
                   :class     (stl/css :pc-remove-btn)
                   :aria-label (tr "workspace.options.plugin-center.uninstall")
                   :title      (tr "workspace.options.plugin-center.uninstall")
                   :on-click   on-uninstall}
          (tr "workspace.options.plugin-center.uninstall")]])]]))

;; ── Menu ────────────────────────────────────────────────────────────────────

(mf/defc plugin-center-menu*
  {::mf/wrap [mf/memo]}
  []
  (let [envelope     (mf/deref registry-envelope)
        status       (mf/deref registry-status)
        enabled      (mf/deref enabled-map)
        plugins      (or (:plugins envelope) [])
        fallback?    (boolean (:fallback envelope))
        loading?     (= status :loading)
        failed?      (= status :failed)

        open?*       (mf/use-state true)
        open?        (deref open?*)
        toggle-open  (mf/use-fn #(swap! open?* not))

        query*       (mf/use-state "")
        query        (deref query*)
        on-query     (mf/use-fn (fn [e]
                                  (reset! query* (.. e -target -value))))

        cats         (categories-from-registry plugins)
        cat*         (mf/use-state "all")
        cat          (deref cat*)
        on-cat       (mf/use-fn (fn [e]
                                  (reset! cat* (.. e -target -value))))

        on-refresh
        (mf/use-fn
         (fn [_]
           (st/emit! (dpc/fetch-registry {}))))

        on-mount
        (mf/use-fn
         (fn []
           ;; Fetch on first mount when the registry slot is empty so the
           ;; panel is populated without a manual refresh.
           (when (nil? envelope)
             (st/emit! (dpc/fetch-registry {})))))]

    (mf/use-effect
     (mf/deps on-mount)
     on-mount)

    (let [visible   (filterv #(plugin-matches? % query cat) plugins)
          installed-ids (set (keys enabled))]

      [:div {:class (stl/css :element-set)}
       [:div {:class (stl/css :element-title)}
        [:> title-bar* {:collapsable (seq plugins)
                        :collapsed    (not open?)
                        :on-collapsed toggle-open
                        :title        (tr "workspace.options.plugin-center.title")}
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "workspace.options.plugin-center.refresh")
                           :on-click on-refresh
                           :icon "refresh"}]]]

       (when open?
         [:div {:class (stl/css :element-set-content)}

          ;; Search + category filter
          [:div {:class (stl/css :pc-filters)}
           [:div {:class (stl/css :pc-search)}
            [:& icon-search]
            [:input {:type "text"
                     :class (stl/css :pc-search-input)
                     :placeholder (tr "workspace.options.plugin-center.search-placeholder")
                     :value query
                     :on-change on-query}]]
           [:select {:class (stl/css :pc-category-select)
                     :value cat
                     :on-change on-cat}
            [:option {:value "all"} (tr "workspace.options.plugin-center.category-all")]
            (for [c cats]
              [:option {:key c :value c} c])]]

          ;; Status chips
          (cond
            loading?  [:div {:class (stl/css :pc-status)} (tr "workspace.options.plugin-center.loading")]
            failed?   [:div {:class (stl/css :pc-status pc-status-error)}
                       (tr "workspace.options.plugin-center.load-failed")]
            fallback? [:div {:class (stl/css :pc-status pc-status-fallback)}
                       (tr "workspace.options.plugin-center.offline")])

          ;; Plugin list
          (if (seq visible)
            [:div {:class (stl/css :pc-list)}
             (for [p visible]
               [:& plugin-row* {:key (:id p)
                                :plugin p
                                :installed? (contains? installed-ids (:id p))
                                :enabled? (boolean (get enabled (:id p)))}])]
            [:div {:class (stl/css :pc-empty)}
             (tr "workspace.options.plugin-center.no-results")])

          [:div {:class (stl/css :pc-hint)}
           (tr "workspace.options.plugin-center.hint")]])])))