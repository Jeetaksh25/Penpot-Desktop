;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL
;;
;; Pathfinder panel -- Illustrator-style Shape Modes + Pathfinders.
;; Shape Modes (Unite / Minus Front / Intersect / Exclude) reuse the
;; existing non-destructive :bool events from `app.main.data.workspace.bool`
;; (`create-bool`, `group-to-bool`, `change-bool-type`).  A normal click is
;; DESTRUCTIVE: it creates the :bool and immediately flattens it to a path
;; via `convert-selected-to-path` (the Figma/Penpot "Expand" semantics that
;; match Illustrator's Pathfinder default).  Alt-click keeps the result as a
;; live, editable :bool (non-destructive).  The 6 Pathfinders (Divide / Trim /
;; Merge / Crop / Outline / Minus-Back) are destructive geometry operations
;; implemented here as local `ptk/reify` WatchEvents: Divide + Outline build a
;; planar arrangement via `app.common.types.path.arrangement`; Trim / Merge /
;; Crop / Minus-Back compose the pairwise boolean ops from
;; `app.common.types.path.bool`.  Every op commits through the standard
;; `pcb/empty-changes` -> `dch/commit-changes` -> `dws/select-shapes` pipeline.

(ns app.main.ui.workspace.sidebar.options.menus.pathfinder
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.types.component :as ctc]
   [app.common.types.container :as ctn]
   [app.common.types.path :as path]
   [app.common.types.path.arrangement :as arrangement]
   [app.common.types.path.bool :as bool]
   [app.common.types.path.helpers :as helpers]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.bool :as dwb]
   [app.main.data.workspace.path.shapes-to-path :as dwps]
   [app.main.data.workspace.selection :as dws]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

;; ---------------------------------------------------------------------------
;; Lucide-style icons (stroke-width 2, currentColor) for the 6 Pathfinders.
;; The 4 Shape-Mode buttons reuse the shared `i/boolean-*` sprite icons via
;; `icon*`, exactly like `bool.cljs`'s radio row.
;; ---------------------------------------------------------------------------

(def ^:private divide-icon
  [:svg {:class (stl/css :pathfinder-icon)
         :width "16" :height "16" :viewBox "0 0 24 24" :fill "none"
         :stroke "currentColor" :stroke-width "2"
         :stroke-linecap "round" :stroke-linejoin "round"}
   [:rect {:x 3 :y 3 :width 18 :height 18 :rx 1}]
   [:line {:x1 12 :y1 3 :x2 12 :y2 21}]
   [:line {:x1 3 :y1 12 :x2 21 :y2 12}]])

(def ^:private trim-icon
  [:svg {:class (stl/css :pathfinder-icon)
         :width "16" :height "16" :viewBox "0 0 24 24" :fill "none"
         :stroke "currentColor" :stroke-width "2"
         :stroke-linecap "round" :stroke-linejoin "round"}
   [:circle {:cx 6 :cy 6 :r 3}]
   [:circle {:cx 6 :cy 18 :r 3}]
   [:line {:x1 20 :y1 4 :x2 8.12 :y2 15.88}]
   [:line {:x1 14.47 :y1 14.48 :x2 20 :y2 20}]
   [:line {:x1 8.12 :y1 8.12 :x2 12 :y2 12}]])

(def ^:private merge-icon
  [:svg {:class (stl/css :pathfinder-icon)
         :width "16" :height "16" :viewBox "0 0 24 24" :fill "none"
         :stroke "currentColor" :stroke-width "2"
         :stroke-linecap "round" :stroke-linejoin "round"}
   [:circle {:cx 18 :cy 18 :r 3}]
   [:circle {:cx 6 :cy 6 :r 3}]
   [:path {:d "M6 21V9a9 9 0 0 0 9 9"}]])

(def ^:private crop-icon
  [:svg {:class (stl/css :pathfinder-icon)
         :width "16" :height "16" :viewBox "0 0 24 24" :fill "none"
         :stroke "currentColor" :stroke-width "2"
         :stroke-linecap "round" :stroke-linejoin "round"}
   [:path {:d "M6 2v14a2 2 0 0 0 2 2h14"}]
   [:path {:d "M18 22V8a2 2 0 0 0-2-2H2"}]])

(def ^:private outline-icon
  [:svg {:class (stl/css :pathfinder-icon)
         :width "16" :height "16" :viewBox "0 0 24 24" :fill "none"
         :stroke "currentColor" :stroke-width "2"
         :stroke-linecap "round" :stroke-linejoin "round"}
   [:rect {:x 3 :y 3 :width 18 :height 18 :rx 1 :stroke-dasharray "3 3"}]])

(def ^:private minus-back-icon
  [:svg {:class (stl/css :pathfinder-icon)
         :width "16" :height "16" :viewBox "0 0 24 24" :fill "none"
         :stroke "currentColor" :stroke-width "2"
         :stroke-linecap "round" :stroke-linejoin "round"}
   [:rect {:x 3 :y 7 :width 14 :height 14 :rx 1}]
   [:rect {:x 7 :y 3 :width 14 :height 14 :rx 1}]
   [:line {:x1 3 :y1 21 :x2 21 :y2 21}]])

;; ---------------------------------------------------------------------------
;; Shape selection + path-content helpers
;; ---------------------------------------------------------------------------

(defn- pathfinder-shapes
  "Ordered (bottom->top z-order) seq of selected shapes eligible for
  pathfinder ops, mirroring the filter `app.main.data.workspace.bool/create-bool`
  applies (no frames, no component variants, no shapes inside a copy tree)."
  [objects ids]
  (->> (cfh/order-by-indexed-shapes objects ids)
       (map #(get objects %))
       (remove cfh/frame-shape?)
       (remove ctc/is-variant?)
       (remove #(ctn/has-any-copy-parent? objects %))
       not-empty))

(defn- shape-content
  "Plain path-content (a PathData instance, which is ISeqable so the
  arrangement + bool engines can consume it directly) for a single shape,
  converting non-path shapes via the shared `path/convert-to-path`."
  [shape objects]
  (:content (path/convert-to-path shape objects)))

(defn- shape-contents
  [shapes objects]
  (mapv #(shape-content % objects) shapes))

(defn- arrangement-input
  "Build the `{:id :content}` input vector expected by
  `arrangement/compute-arrangement` from the selection."
  [shapes objects]
  (mapv (fn [s] {:id (:id s) :content (shape-content s objects)})
        shapes))

(defn- make-path-shape
  "Construct a fully-initialised :path shape from `content` (a plain vector
  of command maps or a PathData), placed as a sibling of `head` and
  inheriting head's transform / flip so the result lands in the same
  parent coordinate space as the existing boolean engine produces."
  [content head fills strokes name]
  (let [base {:type      :path
              :id        (uuid/next)
              :name      name
              :parent-id (:parent-id head)
              :frame-id  (:frame-id head)
              :fills     fills
              :strokes   strokes
              :content   content}
        with-xform
        (cond-> base
          (:transform head)
          (assoc :transform (:transform head)
                 :transform-inverse (:transform-inverse head))
          (:flip-x head) (assoc :flip-x (:flip-x head))
          (:flip-y head) (assoc :flip-y (:flip-y head)))]
    (cts/setup-shape with-xform)))

(defn- commit-replace-selection
  "Build + return the rx stream that removes the old selected shapes and
  inserts `new-shapes` in their place (starting just after the head shape's
  position in its parent), then selects the new shapes."
  [it page-id objects old-ids new-shapes head]
  (let [head-id  (:id head)
        base-idx (inc (cfh/get-position-on-parent objects head-id))
        new-ids  (into (d/ordered-set) (map :id) new-shapes)
        changes
        (as-> (pcb/empty-changes it page-id) ch
          (pcb/with-objects ch objects)
          (pcb/remove-objects ch (vec old-ids))
          (reduce-kv (fn [ch idx shape]
                       (pcb/add-object ch shape {:index (+ base-idx idx)}))
                     ch (vec new-shapes)))]
    (rx/of (dch/commit-changes changes)
           (dws/select-shapes new-ids))))

;; ---------------------------------------------------------------------------
;; Pathfinder op events (local ptk/reify WatchEvents)
;; ---------------------------------------------------------------------------

;; Divide -- replace the selection with one closed path per bounded face of
;; the planar arrangement.  Each face inherits fills/strokes from the
;; topmost (highest z-order) source shape whose sub-paths contain it.
(defn- divide-selection
  []
  (ptk/reify ::divide-selection
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state)
            ids     (dsh/get-selected-ids state)
            shapes  (pathfinder-shapes objects ids)]
        (when (and shapes (seq (rest shapes)))
          (let [head       (first shapes)
                arr-input  (arrangement-input shapes objects)
                arr        (arrangement/compute-arrangement arr-input)
                faces      (arrangement/divide-into-faces arr)
                topfirst   (reverse shapes) ;; topmost (highest z) first
                pick-owner (fn [face]
                             (let [sids (into #{} (map :shape-id)
                                              (:contains-subpaths face))]
                               (or (some #(when (sids (:id %)) %) topfirst)
                                   head)))
                new-shapes
                (mapv (fn [face]
                        (let [owner (pick-owner face)]
                          (make-path-shape (:content face)
                                           head
                                           (:fills owner)
                                           (:strokes owner)
                                           "Divide")))
                      faces)]
            (when (seq new-shapes)
              (commit-replace-selection it page-id objects ids new-shapes head))))))))

;; Trim -- for each shape (bottom->top) keep only the part not covered by
;; the union of the shapes below it; drop strokes.  Approximates
;; Illustrator's Trim (remove hidden parts + strokes).
(defn- trim-selection
  []
  (ptk/reify ::trim-selection
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state)
            ids     (dsh/get-selected-ids state)
            shapes  (pathfinder-shapes objects ids)]
        (when (and shapes (seq (rest shapes)))
          (let [head     (first shapes)
                contents (shape-contents shapes objects)
                new-shapes
                (into
                 []
                 (map-indexed
                  (fn [idx content]
                    (let [below (subvec contents 0 idx)
                          owner (nth shapes idx)]
                      (if (empty? below)
                        (make-path-shape content head
                                         (:fills owner) [] "Trim")
                        (let [union-below (bool/calculate-content :union below)
                              trimmed     (bool/content-bool-pair
                                           :difference content union-below)]
                          (make-path-shape trimmed head
                                           (:fills owner) [] "Trim")))))
                  contents))]
            (when (seq new-shapes)
              (commit-replace-selection it page-id objects ids new-shapes head))))))))

;; Merge -- unite shapes that share the same fill into a single path, one
;; result per fill group.
(defn- merge-selection
  []
  (ptk/reify ::merge-selection
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state)
            ids     (dsh/get-selected-ids state)
            shapes  (pathfinder-shapes objects ids)]
        (when (and shapes (seq (rest shapes)))
          (let [head        (first shapes)
                groups      (group-by :fills shapes)
                new-shapes
                (into
                 []
                 (map (fn [[fills group-shapes]]
                        (let [contents (shape-contents group-shapes objects)
                              merged   (bool/calculate-content :union contents)]
                          (make-path-shape merged head
                                           (or fills (:fills head)) [] "Merge")))
                groups))]
            (when (seq new-shapes)
              (commit-replace-selection it page-id objects ids new-shapes head))))))))

;; Crop -- the topmost shape clips every shape below it; each lower shape is
;; intersected with the top shape and keeps its own fill.  Strokes dropped.
(defn- crop-selection
  []
  (ptk/reify ::crop-selection
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state)
            ids     (dsh/get-selected-ids state)
            shapes  (pathfinder-shapes objects ids)]
        (when (and shapes (seq (rest shapes)))
          (let [head        (first shapes)
                vshapes     (vec shapes)
                top         (peek vshapes)
                top-content (shape-content top objects)
                others      (pop vshapes)
                new-shapes
                (into
                 []
                 (map (fn [shape]
                        (let [content (:content (path/convert-to-path shape objects))
                              cropped (bool/content-bool-pair
                                       :intersection content top-content)]
                          (make-path-shape cropped head
                                           (:fills shape) [] "Crop"))))
                 others)]
            (when (seq new-shapes)
              (commit-replace-selection it page-id objects ids new-shapes head))))))))

;; Outline -- turn the arrangement's EDGES into unfilled, stroked open path
;; segments (one path per edge).  Inherits the head shape's stroke, or a
;; default 1px black stroke when the head has none.
(defn- edge->open-content
  [edge]
  (let [from (:from-point edge)
        to   (:to-point edge)]
    (cond-> [(helpers/make-move-to from)]
      (= :line-to (:command edge))
      (conj (helpers/make-line-to to))
      (= :curve-to (:command edge))
      (conj (helpers/make-curve-to to (:h1 edge) (:h2 edge))))))

(defn- outline-selection
  []
  (ptk/reify ::outline-selection
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state)
            ids     (dsh/get-selected-ids state)
            shapes  (pathfinder-shapes objects ids)]
        (when (and shapes (seq (rest shapes)))
          (let [head      (first shapes)
                arr-input (arrangement-input shapes objects)
                arr       (arrangement/compute-arrangement arr-input)
                edges     (:edges arr)
                stroke    (or (seq (:strokes head))
                              [{:stroke-color "#000000"
                                :stroke-opacity 1
                                :stroke-width 1
                                :stroke-style :solid
                                :stroke-alignment :center}])
                new-shapes
                (into
                 []
                 (map (fn [edge]
                        (make-path-shape (edge->open-content edge)
                                         head [] stroke "Outline")))
                 edges)]
            (when (seq new-shapes)
              (commit-replace-selection it page-id objects ids new-shapes head))))))))

;; Minus-Back -- bottom shape minus top shape (reverse difference).
(defn- minus-back-selection
  []
  (ptk/reify ::minus-back-selection
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state)
            ids     (dsh/get-selected-ids state)
            shapes  (pathfinder-shapes objects ids)]
        (when (and shapes (seq (rest shapes)))
          (let [head           (first shapes)          ;; bottom-most
                top            (peek (vec shapes))     ;; top-most
                bottom-content (shape-content head objects)
                top-content    (shape-content top objects)
                result         (bool/content-bool-pair
                                :difference bottom-content top-content)
                new-shape      (make-path-shape result head
                                               (:fills head) [] "Minus Back")]
            (commit-replace-selection it page-id objects ids [new-shape] head)))))))

;; ---------------------------------------------------------------------------
;; Component
;; ---------------------------------------------------------------------------

(mf/defc pathfinder-options*
  [{:keys [total-selected shapes shapes-with-children]}]
  (let [head            (first shapes)
        head-id         (dm/get-prop head :id)

        is-group?       (cfh/group-shape? head)
        is-bool?        (cfh/bool-shape? head)
        head-bool-type  (and is-bool? (get head :bool-type))

        render-wasm-enabled? (features/use-feature "render-wasm/v1")

        has-invalid-shapes?
        (some (if render-wasm-enabled?
                cfh/frame-shape?
                #(or (cfh/frame-shape? %) (cfh/text-shape? %)))
              shapes-with-children)

        head-not-group-like?
        (and (= 1 total-selected)
             (not is-group?)
             (not is-bool?))

        disabled-shape-modes (or (zero? total-selected)
                                 has-invalid-shapes?
                                 head-not-group-like?)
        disabled-pathfinders (or (<= total-selected 1)
                                 has-invalid-shapes?)

        on-shape-mode
        (mf/use-fn
         (mf/deps total-selected is-group? is-bool? head-id head-bool-type)
         (fn [bool-type alt]
           (let [bt (keyword bool-type)]
             (cond
               (> total-selected 1)
               (if alt
                 (st/emit! (dwb/create-bool bt))
                 (st/emit! (dwb/create-bool bt)
                           (dwps/convert-selected-to-path)))

               (and (= total-selected 1) is-group?)
               (if alt
                 (st/emit! (dwb/group-to-bool head-id bt))
                 (st/emit! (dwb/group-to-bool head-id bt)
                           (dwps/convert-selected-to-path)))

               (and (= total-selected 1) is-bool?)
               (if (= head-bool-type bt)
                 (st/emit! (dwb/bool-to-group head-id))
                 (if alt
                   (st/emit! (dwb/change-bool-type head-id bt))
                   (st/emit! (dwb/change-bool-type head-id bt)
                             (dwps/convert-selected-to-path))))))))

        on-mode-click
        (fn [type]
          (fn [e]
            (on-shape-mode type (.-altKey e))))

        on-divide     (mf/use-fn #(st/emit! (divide-selection)))
        on-trim       (mf/use-fn #(st/emit! (trim-selection)))
        on-merge      (mf/use-fn #(st/emit! (merge-selection)))
        on-crop       (mf/use-fn #(st/emit! (crop-selection)))
        on-outline    (mf/use-fn #(st/emit! (outline-selection)))
        on-minus-back (mf/use-fn #(st/emit! (minus-back-selection)))]

    (when (or (not disabled-shape-modes) (not disabled-pathfinders))
      [:div {:class (stl/css :pathfinder-options)}
       [:div {:class (stl/css :pathfinder-row)}
        [:span {:class (stl/css :pathfinder-label)}
         (tr "workspace.shape.pathfinder.shape-modes")]
        [:div {:class (stl/css :pathfinder-btn-group)}
         [:button {:type "button"
                   :title (tr "workspace.shape.pathfinder.unite")
                   :class (stl/css-case :pathfinder-btn true
                                        :disabled disabled-shape-modes)
                   :disabled disabled-shape-modes
                   :on-click (on-mode-click :union)}
          [:> icon* {:icon-id i/boolean-union :aria-hidden true}]]
         [:button {:type "button"
                   :title (tr "workspace.shape.pathfinder.minus-front")
                   :class (stl/css-case :pathfinder-btn true
                                        :disabled disabled-shape-modes)
                   :disabled disabled-shape-modes
                   :on-click (on-mode-click :difference)}
          [:> icon* {:icon-id i/boolean-difference :aria-hidden true}]]
         [:button {:type "button"
                   :title (tr "workspace.shape.pathfinder.intersect")
                   :class (stl/css-case :pathfinder-btn true
                                        :disabled disabled-shape-modes)
                   :disabled disabled-shape-modes
                   :on-click (on-mode-click :intersection)}
          [:> icon* {:icon-id i/boolean-intersection :aria-hidden true}]]
         [:button {:type "button"
                   :title (tr "workspace.shape.pathfinder.exclude")
                   :class (stl/css-case :pathfinder-btn true
                                        :disabled disabled-shape-modes)
                   :disabled disabled-shape-modes
                   :on-click (on-mode-click :exclude)}
          [:> icon* {:icon-id i/boolean-exclude :aria-hidden true}]]]]
       [:span {:class (stl/css :pathfinder-hint)}
        (tr "workspace.shape.pathfinder.alt-non-destructive")]

       [:div {:class (stl/css :pathfinder-row)}
        [:span {:class (stl/css :pathfinder-label)}
         (tr "workspace.shape.pathfinder.pathfinders")]
        [:div {:class (stl/css :pathfinder-btn-group)}
         [:button {:type "button"
                   :title (tr "workspace.shape.pathfinder.divide")
                   :class (stl/css-case :pathfinder-btn true
                                        :disabled disabled-pathfinders)
                   :disabled disabled-pathfinders
                   :on-click on-divide}
          divide-icon]
         [:button {:type "button"
                   :title (tr "workspace.shape.pathfinder.trim")
                   :class (stl/css-case :pathfinder-btn true
                                        :disabled disabled-pathfinders)
                   :disabled disabled-pathfinders
                   :on-click on-trim}
          trim-icon]
         [:button {:type "button"
                   :title (tr "workspace.shape.pathfinder.merge")
                   :class (stl/css-case :pathfinder-btn true
                                        :disabled disabled-pathfinders)
                   :disabled disabled-pathfinders
                   :on-click on-merge}
          merge-icon]
         [:button {:type "button"
                   :title (tr "workspace.shape.pathfinder.crop")
                   :class (stl/css-case :pathfinder-btn true
                                        :disabled disabled-pathfinders)
                   :disabled disabled-pathfinders
                   :on-click on-crop}
          crop-icon]
         [:button {:type "button"
                   :title (tr "workspace.shape.pathfinder.outline")
                   :class (stl/css-case :pathfinder-btn true
                                        :disabled disabled-pathfinders)
                   :disabled disabled-pathfinders
                   :on-click on-outline}
          outline-icon]
         [:button {:type "button"
                   :title (tr "workspace.shape.pathfinder.minus-back")
                   :class (stl/css-case :pathfinder-btn true
                                        :disabled disabled-pathfinders)
                   :disabled disabled-pathfinders
                   :on-click on-minus-back}
          minus-back-icon]]]])))