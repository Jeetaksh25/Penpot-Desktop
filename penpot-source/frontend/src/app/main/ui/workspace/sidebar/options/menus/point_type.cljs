;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL
;;
;; ALL_APPS_PARITY P2.18 — Explicit 4 vector point-type Inspector.
;;
;; A compact 4-button row shown in the path-edit toolbar (rendered from
;; `app.main.ui.workspace.viewport.path-actions`) when at least one path
;; node is selected in :move edit-mode. The four types mirror Sketch /
;; Illustrator node behavior:
;;
;;   1  Straight             — corner, no Bezier handles
;;   2  Mirror angle+length  — symmetric handles (collinear + equal length)
;;   3  Independent          — fully independent handles
;;   4  Mirror angle         — collinear handles, independent lengths
;;
;; The currently-selected node's EFFECTIVE type (the explicit
;; `:point-types` entry on the shape, falling back to the geometry-
;; inferred type) is highlighted. A click emits the
;; `drp/set-point-type` event, which adjusts handle geometry and persists
;; the explicit type (see tools.cljs / segment.cljc). Number keys 1-4 are
;; bound to the same event in shortcuts.cljs (:path-editor subsection).

(ns app.main.ui.workspace.sidebar.options.menus.point-type
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.common.types.path :as path]
   [app.main.data.workspace.path :as drp]
   [app.main.store :as st]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; The 4 point-type icons are inline Lucide-style SVGs (stroke-width 2,
;; currentColor) — there are no matching deprecated-icon xrefs, so we
;; author the glyphs directly per the project Lucide-icons convention.

(def ^:private straight-icon
  (mf/html [:svg {:class (stl/css :point-type-icon)
                  :width "16" :height "16" :viewBox "0 0 24 24" :fill "none"
                  :stroke "currentColor" :stroke-width "2"
                  :stroke-linecap "round" :stroke-linejoin "round"}
            ;; A corner point: two segments meeting at a right angle, no handles.
            [:path {:d "M4 20L12 4L20 20"}]]))

(def ^:private mirror-angle-length-icon
  (mf/html [:svg {:class (stl/css :point-type-icon)
                  :width "16" :height "16" :viewBox "0 0 24 24" :fill "none"
                  :stroke "currentColor" :stroke-width "2"
                  :stroke-linecap "round" :stroke-linejoin "round"}
            ;; A smooth point: symmetric handles of equal length through the node.
            [:circle {:cx 12 :cy 12 :r 1.6}]
            [:line {:x1 12 :y1 12 :x2 4 :y2 8}]
            [:line {:x1 12 :y1 12 :x2 20 :y2 16}]
            [:path {:d "M5 6C7 7 7 7 4 8"}]
            [:path {:d "M19 14C17 15 17 15 20 16"}]]))

(def ^:private independent-icon
  (mf/html [:svg {:class (stl/css :point-type-icon)
                  :width "16" :height "16" :viewBox "0 0 24 24" :fill "none"
                  :stroke "currentColor" :stroke-width "2"
                  :stroke-linecap "round" :stroke-linejoin "round"}
            ;; A corner with two independent (non-collinear) handles.
            [:circle {:cx 12 :cy 12 :r 1.6}]
            [:line {:x1 12 :y1 12 :x2 5 :y2 6}]
            [:line {:x1 12 :y1 12 :x2 18 :y2 7}]]))

(def ^:private mirror-angle-icon
  (mf/html [:svg {:class (stl/css :point-type-icon)
                  :width "16" :height "16" :viewBox "0 0 24 24" :fill "none"
                  :stroke "currentColor" :stroke-width "2"
                  :stroke-linecap "round" :stroke-linejoin "round"}
            ;; Collinear handles of unequal length through the node.
            [:circle {:cx 12 :cy 12 :r 1.6}]
            [:line {:x1 12 :y1 12 :x2 3 :y2 8}]
            [:line {:x1 12 :y1 12 :x2 20 :y2 15}]]))

(def ^:private type->icon
  {:straight            straight-icon
   :mirror-angle-length mirror-angle-length-icon
   :independent         independent-icon
   :mirror-angle        mirror-angle-icon})

(def ^:private type->label-key
  {:straight            "workspace.path.point-type.straight"
   :mirror-angle-length "workspace.path.point-type.mirror-angle-length"
   :independent         "workspace.path.point-type.independent"
   :mirror-angle        "workspace.path.point-type.mirror-angle"})

(def ^:private type->shortcut-key
  {:straight            "workspace.path.point-type.straight.shortcut"
   :mirror-angle-length "workspace.path.point-type.mirror-angle-length.shortcut"
   :independent         "workspace.path.point-type.independent.shortcut"
   :mirror-angle        "workspace.path.point-type.mirror-angle.shortcut"})

(def ^:private type-order
  [:straight :mirror-angle-length :independent :mirror-angle])

(defn- point-type-key
  "Stable string key for a path node — matches the key used by
  tools/set-point-type so the explicit :point-types map resolves."
  [p]
  (dm/str (mth/round (dm/get-prop p :x) 0.01) ","
          (mth/round (dm/get-prop p :y) 0.01)))

(defn- effective-type
  "Returns the effective point-type for a node `point` on `shape`:
  the explicit `:point-types` entry when present, else the geometry-
  inferred type from `content`."
  [shape content point]
  (let [pt-map (get shape :point-types)
        k      (point-type-key point)]
    (or (get pt-map k)
        (path/infer-point-type content point))))

(mf/defc point-type-options*
  {::mf/private true}
  [{:keys [shape state]}]
  (let [{:keys [edit-mode selected-points]} state
        content (:content shape)
        nodes   (when (and (= edit-mode :move) (seq selected-points))
                  (seq selected-points))]
    (when nodes
      ;; When a single node is selected, highlight its effective type.
      ;; With multiple nodes selected, no highlight (mixed) — clicking
      ;; still applies the chosen type to all selected nodes.
      (let [single?      (= (count nodes) 1)
            current      (when single?
                           (effective-type shape content (first nodes)))
            on-pick      (mf/use-fn
                          (mf/deps nodes)
                          (fn [ptype]
                            (st/emit! (drp/set-point-type ptype))))]
        [:div {:class (stl/css :point-type-options)}
         [:span {:class (stl/css :point-type-label)}
          (tr "workspace.path.point-type.label")]
         [:div {:class (stl/css :point-type-btn-group)}
          (for [ptype type-order]
            (let [icon      (get type->icon ptype)
                  label-key (get type->label-key ptype)
                  tip       (str (tr label-key)
                                 " ("
                                 (tr (get type->shortcut-key ptype))
                                 ")")
                  active?   (and single? (= current ptype))]
              [:button {:key        (name ptype)
                        :type       "button"
                        :title      tip
                        :class      (stl/css-case :point-type-btn true
                                                  :active active?)
                        :on-click   #(on-pick ptype)}
               icon]))]]))))