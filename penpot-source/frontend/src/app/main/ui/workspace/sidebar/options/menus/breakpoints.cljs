;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.breakpoints
  "ALL_APPS_PARITY P0.15 — Responsive breakpoints sidebar menu.

  Reads the first selected frame's `:breakpoints` (defaults to
  `make-default-breakpoints` for display when absent) and renders:
    * a header row with an add button,
    * a row per breakpoint (name + width, coral active toggle, remove),
    * a short hint line about per-breakpoint style overrides.

  Lucide icons are inlined (viewBox 0 0 24 24, stroke-width 2,
  currentColor, no emoji). Coral accent #f28b82 for active toggles.
  i18n keys are placeholders — the lead adds them to en.po."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.breakpoint :as ctbp]
   [app.main.data.workspace.breakpoints :as dwb]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; ── Inline Lucide icons ────────────────────────────────────────────────────
;; viewBox "0 0 24 24", stroke-width 2, currentColor, no emoji.

(defn- lucide-plus
  "Lucide `plus` icon."
  []
  (mf/html [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
                  :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
                  :width 16 :height 16 :class (stl/css :lucide-icon)}
            [:line {:x1 12 :y1 5 :x2 12 :y2 19}]
            [:line {:x1 5 :y1 12 :x2 19 :y2 12}]]))

(defn- lucide-trash-2
  "Lucide `trash-2` icon."
  []
  (mf/html [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
                  :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
                  :width 16 :height 16 :class (stl/css :lucide-icon)}
            [:polyline {:points "3 6 5 6 21 6"}]
            [:path {:d "M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"}]
            [:path {:d "M10 11v6"}]
            [:path {:d "M14 11v6"}]
            [:path {:d "M9 6V4a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2"}]]))

;; ── Breakpoint row ─────────────────────────────────────────────────────────

(mf/defc breakpoint-row*
  {::mf/wrap [mf/memo]}
  [{:keys [shape-id breakpoint]}]
  (let [{:keys [id name width base?]} breakpoint

        on-remove
        (mf/use-fn
         (mf/deps shape-id id)
         (fn [_]
           (st/emit! (dwb/remove-breakpoint {:id id}))))

        on-toggle-active
        (mf/use-fn
         (mf/deps shape-id id)
         (fn [_]
           ;; Toggling "active" is a UI affordance for which breakpoint
           ;; is the editing target. v1 keeps it client-side; the data
           ;; event wiring is deferred (no active-state field on the
           ;; frame yet). The coral toggle reflects :base? as the active
           ;; indicator so it is honest about persisted state.
           ))]

    [:div {:class (stl/css :breakpoint-row)}
     [:div {:class (stl/css :breakpoint-info)}
      [:span {:class (stl/css :breakpoint-name)} (or name (tr "workspace.options.breakpoints.name"))]
      [:span {:class (stl/css :breakpoint-width)}
       (dm/str (or width 0) "px")]]
     [:div {:class (stl/css :breakpoint-actions)}
      [:button {:type "button"
                :class (stl/css-case :bp-toggle true
                                     :bp-toggle-active (boolean base?))
                :aria-pressed (boolean base?)
                :title (tr "workspace.options.breakpoints.width")
                :on-click on-toggle-active}
       [:span {:class (stl/css :bp-toggle-dot)}]]
      [:button {:type "button"
                :class (stl/css :bp-remove-btn)
                :aria-label (tr "workspace.options.breakpoints.remove")
                :on-click on-remove}
       [:& lucide-trash-2]]]]))

;; ── Menu ───────────────────────────────────────────────────────────────────

(mf/defc breakpoints-menu*
  {::mf/wrap [mf/memo]}
  [{:keys [shapes]}]
  (let [frame     (first shapes)
        raw-bps   (:breakpoints frame)
        bps       (or raw-bps (ctbp/make-default-breakpoints))
        items     (:items bps)
        open?*    (mf/use-state true)
        open?     (deref open?*)
        toggle-open (mf/use-fn #(swap! open?* not))

        on-add
        (mf/use-fn
         (mf/deps (:id frame))
         (fn [_]
           (st/emit! (dwb/add-breakpoint {:width 1280 :name (tr "workspace.options.breakpoints.desktop")}))))]

    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable (seq items)
                      :collapsed    (not open?)
                      :on-collapsed toggle-open
                      :title        (tr "workspace.options.breakpoints.title")}
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.breakpoints.add")
                         :on-click on-add
                         :icon "add"}]]]

     (when (and open? (seq items))
       [:div {:class (stl/css :element-set-content)}
        (for [bp items]
          [:& breakpoint-row* {:key (str (:id frame) "-" (:id bp))
                               :shape-id (:id frame)
                               :breakpoint bp}])
        [:div {:class (stl/css :breakpoint-hint)}
         (tr "workspace.options.breakpoints.hint")]])]))