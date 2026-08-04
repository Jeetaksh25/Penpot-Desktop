;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.viewport.path-actions
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.path.segment :as path.segm]
   [app.main.data.workspace.path :as drp]
   [app.main.data.workspace.path.shortcuts :as sc]
   [app.main.data.workspace.path.shapes-to-path :as dwps]
   [app.main.store :as st]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private pentool-icon
  (deprecated-icon/icon-xref :pentool (stl/css :pentool-icon :pathbar-icon)))

(def ^:private move-icon
  (deprecated-icon/icon-xref :move (stl/css :move-icon :pathbar-icon)))

(def ^:private add-icon
  (deprecated-icon/icon-xref :add (stl/css :add-icon :pathbar-icon)))

(def ^:private remove-icon
  (deprecated-icon/icon-xref :remove (stl/css :remove :pathbar-icon)))

(def ^:private merge-nodes-icon
  (deprecated-icon/icon-xref :merge-nodes (stl/css :merge-nodes-icon :pathbar-icon)))

(def ^:private join-nodes-icon
  (deprecated-icon/icon-xref :join-nodes (stl/css :join-nodes-icon :pathbar-icon)))

(def ^:private separate-nodes-icon
  (deprecated-icon/icon-xref :separate-nodes (stl/css :separate-nodes-icon :pathbar-icon)))

(def ^:private to-corner-icon
  (deprecated-icon/icon-xref :to-corner (stl/css :to-corner-icon :pathbar-icon)))

(def ^:private to-curve-icon
  (deprecated-icon/icon-xref :to-curve (stl/css :to-curve-icon :pathbar-icon)))

(def ^:private snap-nodes-icon
  (deprecated-icon/icon-xref :snap-nodes (stl/css :snap-nodes-icon :pathbar-icon)))

;; Figma-parity vector-network tool icons (gaps #28/#29). Reuse existing
;; icon xrefs; the interactive geometry behind these modes is DEFERRED.
(def ^:private shape-builder-icon
  (deprecated-icon/icon-xref :boolean-union (stl/css :merge-nodes-icon :pathbar-icon)))

(def ^:private paint-bucket-icon
  (deprecated-icon/icon-xref :fill-content (stl/css :merge-nodes-icon :pathbar-icon)))

;; Figma-parity Scissors tool (ALL_APPS_PARITY P2.32). Inline Lucide
;; "scissors" SVG (stroke-width 2, currentColor) — there is no matching
;; deprecated-icon xref, so we author the glyph directly per the project
;; Lucide-icons convention. The tool splits a segment at the nearest point
;; to a click (see shapes/path/editor.cljs ::on-scissors-pointer-down).
(def ^:private scissors-icon
  [:svg {:class (stl/css :pathbar-icon)
         :viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :stroke-width 2
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :aria-hidden true}
   [:circle {:cx 6 :cy 6 :r 3}]
   [:circle {:cx 6 :cy 18 :r 3}]
   [:line {:x1 20 :y1 4 :x2 8.12 :y2 15.88}]
   [:line {:x1 14.47 :y1 14.48 :x2 20 :y2 20}]
   [:line {:x1 8.12 :y1 8.12 :x2 12 :y2 12}]])

;; Figma-parity Offset vector (#55) / Simplify vector (#56) icons. Reuse
;; existing icon xrefs; the offset/simplify math lives in shapes_to_path.cljs.
(def ^:private offset-vector-icon
  (deprecated-icon/icon-xref :move (stl/css :move-icon :pathbar-icon)))

(def ^:private simplify-vector-icon
  (deprecated-icon/icon-xref :remove (stl/css :remove :pathbar-icon)))

(defn check-enabled [content selected-points]
  (when content
    (let [segments (path.segm/get-segments-with-points content selected-points)
          num-segments (count segments)
          num-points (count selected-points)
          points-selected? (seq selected-points)
          segments-selected? (seq segments)
          ;; max segments for n points is (n × (n -1)) / 2
          max-segments (-> num-points
                           (* (- num-points 1))
                           (/ 2))
          is-curve? (some #(path.segm/is-curve? content %) selected-points)]

      {:make-corner (and points-selected? is-curve?)
       :make-curve (and points-selected? (not is-curve?))
       :add-node segments-selected?
       :remove-node points-selected?
       :merge-nodes segments-selected?
       :join-nodes (and points-selected? (>= num-points 2) (< num-segments max-segments))
       :separate-nodes segments-selected?})))

(mf/defc path-actions*
  [{:keys [shape state]}]
  (let [{:keys [edit-mode selected-points snap-toggled]} state

        content (:content shape)

        ;; Figma-parity Simplify vector (#56) threshold/intensity. Local
        ;; component state; the value is the RDP perpendicular-distance
        ;; epsilon in path units. Additive — only read by the simplify
        ;; button below.
        simplify-threshold* (mf/use-state 1.0)

        enabled-buttons
        (mf/use-memo
         (mf/deps content selected-points)
         #(check-enabled content selected-points))

        on-select-draw-mode
        (mf/use-fn
         (fn [_]
           (st/emit! (drp/change-edit-mode :draw))))

        on-select-edit-mode
        (mf/use-fn
         (fn [_]
           (st/emit! (drp/change-edit-mode :move))))

        on-add-node
        (mf/use-fn
         (mf/deps (:add-node enabled-buttons))
         (fn [_]
           (when (:add-node enabled-buttons)
             (st/emit! (drp/add-node)))))

        on-remove-node
        (mf/use-fn
         (mf/deps (:remove-node enabled-buttons))
         (fn [_]
           (when (:remove-node enabled-buttons)
             (st/emit! (drp/remove-node)))))

        on-merge-nodes
        (mf/use-fn
         (mf/deps (:merge-nodes enabled-buttons))
         (fn [_]
           (when (:merge-nodes enabled-buttons)
             (st/emit! (drp/merge-nodes)))))

        on-join-nodes
        (mf/use-fn
         (mf/deps (:join-nodes enabled-buttons))
         (fn [_]
           (when (:join-nodes enabled-buttons)
             (st/emit! (drp/join-nodes)))))

        on-separate-nodes
        (mf/use-fn
         (mf/deps (:separate-nodes enabled-buttons))
         (fn [_]
           (when (:separate-nodes enabled-buttons)
             (st/emit! (drp/separate-nodes)))))

        on-make-corner
        (mf/use-fn
         (mf/deps (:make-corner enabled-buttons))
         (fn [_]
           (when (:make-corner enabled-buttons)
             (st/emit! (drp/make-corner)))))

        on-make-curve
        (mf/use-fn
         (mf/deps (:make-curve enabled-buttons))
         (fn [_]
           (when (:make-curve enabled-buttons)
             (st/emit! (drp/make-curve)))))

        on-toggle-snap
        (mf/use-fn
         (fn [_]
           (st/emit! (drp/toggle-snap))))

        ;; Figma-parity vector-network tools (gaps #28/#29). Toggle the
        ;; edit-mode; the interactive geometry is DEFERRED — see the
        ;; registered no-op render in shapes/path/editor.cljs.
        on-select-shape-builder
        (mf/use-fn
         (fn [_]
           (st/emit! (drp/change-edit-mode :shape-builder))))

        on-select-paint-bucket
        (mf/use-fn
         (fn [_]
           (st/emit! (drp/change-edit-mode :paint-bucket))))

        ;; Figma-parity Scissors tool (ALL_APPS_PARITY P2.32). Toggle the
        ;; :scissors edit-mode. In that mode a click near any segment snaps
        ;; to the nearest point and splits it (handled by
        ;; `on-scissors-pointer-down` in shapes/path/editor.cljs, reusing
        ;; the existing `path/closest-point` + `drp/create-node-at-position`
        ;; primitives — no new data model).
        on-select-scissors
        (mf/use-fn
         (fn [_]
           (st/emit! (drp/change-edit-mode :scissors))))

        ;; Figma-parity Offset vector (#55) / Simplify vector (#56). Apply
        ;; the shapes_to_path ops to the edited path's id. NOTE: these
        ;; operate on the committed shape content via dwsh/update-shapes;
        ;; for a path mid-edit with uncommitted modifiers the edit-mode
        ;; state integration is DEFERRED (would need edition.cljs changes,
        ;; out of scope) — the buttons are additive UI and a safe no-op
        ;; when the shape has no content.
        on-offset-vector
        (mf/use-fn
         (mf/deps (:id shape))
         (fn [_]
           (st/emit! (dwps/offset-vector [(:id shape)] 1.0))))

        on-simplify-vector
        (mf/use-fn
         (mf/deps (:id shape) @simplify-threshold*)
         (fn [_]
           (st/emit! (dwps/simplify-vector [(:id shape)] @simplify-threshold*))))]

    [:div {:class (stl/css :sub-actions)
           :data-dont-clear-path true}
     [:div {:class (stl/css :sub-actions-group)}

      ;; Draw Mode
      [:button {:class  (stl/css-case :is-toggled (= edit-mode :draw)
                                      :topbar-btn true)
                :title (tr "workspace.path.actions.draw-nodes" (sc/get-tooltip :draw-nodes))
                :on-click on-select-draw-mode}
       pentool-icon]

      ;; Edit mode
      [:button {:class (stl/css-case :is-toggled (= edit-mode :move)
                                     :topbar-btn true)
                :title (tr "workspace.path.actions.move-nodes" (sc/get-tooltip :move-nodes))
                :on-click on-select-edit-mode}
       move-icon]]

     [:div {:class (stl/css :sub-actions-group)}
      ;; Add Node
      [:button {:disabled (not (:add-node enabled-buttons))
                :class (stl/css :topbar-btn)
                :title (tr "workspace.path.actions.add-node" (sc/get-tooltip :add-node))
                :on-click on-add-node}
       add-icon]

      ;; Remove node
      [:button {:disabled (not (:remove-node enabled-buttons))
                :class (stl/css :topbar-btn)
                :title (tr "workspace.path.actions.delete-node" (sc/get-tooltip :delete-node))
                :on-click on-remove-node}
       remove-icon]]

     [:div {:class (stl/css :sub-actions-group)}
      ;; Merge Nodes
      [:button {:disabled (not (:merge-nodes enabled-buttons))
                :class (stl/css :topbar-btn)
                :title (tr "workspace.path.actions.merge-nodes" (sc/get-tooltip :merge-nodes))
                :on-click on-merge-nodes}
       merge-nodes-icon]

      ;; Join Nodes
      [:button {:disabled (not (:join-nodes enabled-buttons))
                :class (stl/css :topbar-btn)
                :title (tr "workspace.path.actions.join-nodes" (sc/get-tooltip :join-nodes))
                :on-click on-join-nodes}
       join-nodes-icon]

      ;; Separate Nodes
      [:button {:disabled (not (:separate-nodes enabled-buttons))
                :class (stl/css :topbar-btn)
                :title (tr "workspace.path.actions.separate-nodes" (sc/get-tooltip :separate-nodes))
                :on-click on-separate-nodes}
       separate-nodes-icon]]

     [:div {:class (stl/css :sub-actions-group)}
      ; Make Corner
      [:button {:disabled (not (:make-corner enabled-buttons))
                :class (stl/css :topbar-btn)
                :title (tr "workspace.path.actions.make-corner" (sc/get-tooltip :make-corner))
                :on-click on-make-corner}
       to-corner-icon]

      ;; Make Curve
      [:button {:disabled (not (:make-curve enabled-buttons))
                :class (stl/css :topbar-btn)
                :title (tr "workspace.path.actions.make-curve" (sc/get-tooltip :make-curve))
                :on-click on-make-curve}
       to-curve-icon]]

     ;; Figma-parity vector-network tools (gaps #28/#29). Toggle the
     ;; edit-mode only; interactive merge/extract/subtract (#28, via
     ;; common.types.path/bool) and enclosed-region flood-fill (#29,
     ;; graph-cycle on path topology) are DEFERRED.
     [:div {:class (stl/css :sub-actions-group)}
      ;; Shape builder mode
      [:button {:class  (stl/css-case :is-toggled (= edit-mode :shape-builder)
                                      :topbar-btn true)
                :title (tr "workspace.path.actions.shape-builder" (sc/get-tooltip :shape-builder))
                :on-click on-select-shape-builder}
       shape-builder-icon]

      ;; Paint bucket mode
      [:button {:class  (stl/css-case :is-toggled (= edit-mode :paint-bucket)
                                      :topbar-btn true)
                :title (tr "workspace.path.actions.paint-bucket" (sc/get-tooltip :paint-bucket))
                :on-click on-select-paint-bucket}
       paint-bucket-icon]

      ;; Scissors mode (ALL_APPS_PARITY P2.32) — click a segment to split
      ;; it at the nearest point. Shift+C keeps it Figma-adjacent without
      ;; clashing with :make-curve (plain "c").
      [:button {:class  (stl/css-case :is-toggled (= edit-mode :scissors)
                                      :topbar-btn true)
                :title (tr "workspace.path.actions.scissors" (sc/get-tooltip :scissors))
                :on-click on-select-scissors}
       scissors-icon]]

     ;; Figma-parity Offset vector (#55) / Simplify vector (#56). Apply
     ;; the shapes_to_path ops to the edited path. The simplify threshold
     ;; is a local range control (RDP epsilon in path units).
     [:div {:class (stl/css :sub-actions-group)}
      ;; Offset vector
      [:button {:class  (stl/css :topbar-btn)
                :title (tr "workspace.path.actions.offset-vector")
                :on-click on-offset-vector}
       offset-vector-icon]

      ;; Simplify vector
      [:button {:class  (stl/css :topbar-btn)
                :title (tr "workspace.path.actions.simplify-vector")
                :on-click on-simplify-vector}
       simplify-vector-icon]

      ;; Simplify threshold / intensity
      [:input {:type "range"
               :min 0.1
               :max 20
               :step 0.1
               :value @simplify-threshold*
               :title (tr "workspace.path.actions.simplify-threshold")
               :on-change (fn [event]
                            (let [value (js/Number (dom/get-value (dom/get-current-target event)))]
                              (when-not (js/isNaN value)
                                (reset! simplify-threshold* value))))}]]

     [:div {:class (stl/css :sub-actions-group)}
      ;; Toggle snap
      [:button {:class  (stl/css-case :is-toggled snap-toggled
                                      :topbar-btn true)
                :title (tr "workspace.path.actions.snap-nodes" (sc/get-tooltip :snap-nodes))
                :on-click on-toggle-snap}
       snap-nodes-icon]]]))
