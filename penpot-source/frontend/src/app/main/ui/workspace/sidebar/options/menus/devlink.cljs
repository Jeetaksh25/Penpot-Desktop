;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.devlink
  "P2.16 — DevLink two-way sync inspector menu (ALL_APPS_PARITY).

  A FILE-LEVEL menu (mounted in the zero-selected block of options.cljs)
  showing the registered code-component count and two actions:

    - 'Export DevLink manifest' — copies the DevLinkProvider manifest
      JSON to the clipboard + downloads `devlink-manifest.json` (forward
      sync: Ovion -> code). Emits `devlink/export-manifest`.
    - 'Re-sync from source' — re-pulls Storybook-sourced components via
      `devlink/re-sync-components` (reverse sync: code -> Ovion).

  Self-hides (returns nil) when the file registry is empty — no
  components = nothing to sync. Coral accent, Lucide icons
  (download / refresh-cw / link), reduced-motion safe (no motion
  emitted, only static styles). `byte-identical-when-inactive`: no
  click = no export = no fetch = no registry mutation.

  Consumes the P0.14 host via `app.main.data.workspace.code-components`
  for the registry ref, and `app.main.data.workspace.devlink` for the
  sync events. Does NOT touch plugin-data directly."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.code-components :as dcc]
   [app.main.data.workspace.devlink :as devlink]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; Coral + grey accents (Ovion theme) — matches code-component / connectors menus.
(def ^:private coral "#f28b82")
(def ^:private grey  "#7d7d7d")

(def ^:private coral-btn-style
  {:display "inline-flex"
   :align-items "center"
   :justify-content "center"
   :height "26px"
   :padding "0 10px"
   :border-radius "6px"
   :border "1px solid rgba(242,139,130,0.4)"
   :background "rgba(242,139,130,0.08)"
   :color coral
   :cursor "pointer"
   :font-size "11px"
   :font-weight "500"})

(def ^:private ghost-btn-style
  {:display "inline-flex"
   :align-items "center"
   :justify-content "center"
   :height "26px"
   :padding "0 10px"
   :border-radius "6px"
   :border "1px solid rgba(125,125,125,0.3)"
   :background "transparent"
   :color grey
   :cursor "pointer"
   :font-size "11px"
   :font-weight "500"})

(def ^:private row-style
  {:display "flex" :align-items "center" :gap "8px" :width "100%"})

(def ^:private count-style
  {:font-size "11px" :color grey :margin-left "auto"})

;; --- Lucide icons (lucide.dev, MIT) — 24x24, stroke currentColor, sw 2 ---

(defn- lucide-icon [children]
  [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
         :width 14 :height 14 :style {:flex-shrink "0"}}
   children])

(defn- icon-link
  "Lucide `link` — chain icon."
  []
  (lucide-icon
   [[:path {:d "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"}]
    [:path {:d "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"}]]))

(defn- icon-download
  "Lucide `download`."
  []
  (lucide-icon
   [[:path {:d "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"}]
    [:polyline {:points "7 10 12 15 17 10"}]
    [:line {:x1 12 :y1 15 :x2 12 :y2 3}]]))

(defn- icon-refresh-cw
  "Lucide `refresh-cw` — circular arrows (re-sync)."
  []
  (lucide-icon
   [[:polyline {:points "23 4 23 10 17 10"}]
    [:polyline {:points "1 20 1 14 7 14"}]
    [:path {:d "M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"}]]))

;; --- Derived ref over the file registry -------------------------------------

(defn- registry-ref
  "Derived ref over the workspace file-data code-component registry, so
  the menu re-renders when a component is registered/unregistered."
  []
  (l/derived
   (fn [file-data]
     (dcc/read-registry file-data))
   refs/workspace-data
   =))

;; --- Main menu --------------------------------------------------------------

(mf/defc devlink-menu*
  "DevLink two-way sync menu. FILE-LEVEL (registry-level): mounted in
  the zero-selected block of options.cljs. Accepts `{:keys [shapes]}` for
  API consistency with neighboring menus but does NOT gate on selection
  — DevLink sync is a file/registry concern, not a per-shape one. The
  `shapes` prop is accepted and ignored.

  Self-hides (returns nil) when the registry is empty (no components =
  nothing to sync). Otherwise shows the registered component count and
  two actions: export manifest (forward sync) + re-sync from source
  (reverse sync)."
  [{:keys [shapes]}]
  ;; `shapes` is intentionally unused — DevLink is a file-level/registry
  ;; concern, not a per-shape one. Accepted for API consistency with the
  ;; neighboring menus mounted in the same block.
  (let [registry (mf/deref (registry-ref))
        registry (or registry {})
        count    (count registry)
        show?    (pos? count)]
    (when show?
      (let [on-export
            (mf/use-fn
             (fn []
               (st/emit! (devlink/export-manifest))))

            on-resync
            (mf/use-fn
             (fn []
               (st/emit! (devlink/re-sync-components))))]
        [:div {:class (stl/css :element-set)}
         [:div {:class (stl/css :element-title)}
          [:> title-bar* {:collapsable true :collapsed false
                          :title (tr "workspace.options.devlink.title")}
           [:span {:style {:display "inline-flex" :align-items "center"
                           :margin-left "6px"}}
            (icon-link)]]]

         [:div {:class (stl/css :element-set-content)}
          [:div {:class (stl/css :element-group)
                 :style {:display "flex" :flex-direction "column" :gap "8px"}}

           ;; Registered component count.
           [:div {:style row-style}
            [:span {:style (assoc count-style :margin-left "0")}
             (tr "workspace.options.devlink.registered-count")]
            [:span {:style {:font-size "11px" :color coral
                            :font-weight "600"}}
             (str count)]]

           ;; Export DevLink manifest (forward sync).
           [:div {:style row-style}
            [:button {:type "button" :style coral-btn-style :on-click on-export
                      :title (tr "workspace.options.devlink.export-hint")}
             (icon-download)
             [:span {:style {:margin-left "6px"}}
              (tr "workspace.options.devlink.export")]]]

           ;; Re-sync from source (reverse sync).
           [:div {:style row-style}
            [:button {:type "button" :style ghost-btn-style :on-click on-resync
                      :title (tr "workspace.options.devlink.resync-hint")}
             (icon-refresh-cw)
             [:span {:style {:margin-left "6px"}}
              (tr "workspace.options.devlink.resync")]]]]]]))))