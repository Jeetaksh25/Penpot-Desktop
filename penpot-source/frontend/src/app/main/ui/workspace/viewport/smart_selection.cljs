;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.viewport.smart-selection
  "Smart Selection overlay (Figma parity gap #8).

  Renders pink handles on top of a uniformly-spaced sibling selection.
  This layer is PURELY ADDITIVE and GUARDED: `smart-selection-layer*`
  runs the pure detector from `app.main.data.workspace.smart-selection`
  and returns nil (renders nothing) unless a uniform layout is found.
  No existing selection-handle, resize, rotate or marquee behavior is
  touched by this namespace."
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.smart-selection :as ssm]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

;; Figma's Smart Selection accent is a magenta/pink. Kept as a literal
;; so the overlay does not depend on theme variables that existing
;; selection handles use.
(def ^:private pink-handle "#ff3366")

(defn- x-center [b] (+ (:x b) (/ (:w b) 2)))
(defn- y-center [b] (+ (:y b) (/ (:h b) 2)))

(defn- gap-handles
  "Build the list of inter-item gap handle descriptors for a detected
  layout. Each descriptor is `{:cx :cy :axis}` in canvas space. For a
  grid we emit one handle per intra-row x-gap and one per inter-row
  y-gap, so the user can tidy spacing on both axes."
  [layout]
  (case (:axis layout)
    :horizontal
    (let [sorted (sort-by :x (:items layout))]
      (for [[a b] (partition 2 1 sorted)]
        {:cx   (/ (+ (+ (:x a) (:w a)) (:x b)) 2)
         :cy   (y-center a)
         :axis :horizontal}))

    :vertical
    (let [sorted (sort-by :y (:items layout))]
      (for [[a b] (partition 2 1 sorted)]
        {:cx   (x-center a)
         :cy   (/ (+ (+ (:y a) (:h a)) (:y b)) 2)
         :axis :vertical}))

    :grid
    (let [rows (:rows layout)]
      (concat
       ;; x-gaps within each row
       (for [row rows
             [a b] (partition 2 1 (sort-by :x row))]
         {:cx   (/ (+ (+ (:x a) (:w a)) (:x b)) 2)
          :cy   (y-center a)
          :axis :horizontal})
       ;; y-gaps between consecutive rows (anchored to the first column)
       (for [[ra rb] (partition 2 1 rows)]
         (let [ca (first (sort-by :x ra))
               cb (first (sort-by :x rb))]
           {:cx   (x-center cb)
            :cy   (/ (+ (+ (:y ca) (:h ca)) (:y cb)) 2)
            :axis :vertical}))))))

(mf/defc smart-selection-overlay*
  {::mf/private true
   ::mf/wrap-props false}
  [{:keys [layout zoom]}]
  (let [items (:items layout)
        gaps  (gap-handles layout)

        on-h-spacing
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dw/distribute-objects :horizontal))))

        on-v-spacing
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dw/distribute-objects :vertical))))]
    [:g.smart-selection {:pointer-events "none"}
     ;; Pink rings around each item center (rearrange affordance, visual only)
     (for [b items]
       [:circle.smart-selection-ring
        {:key  (dm/str "ss-ring-" (:id b))
         :cx   (x-center b)
         :cy   (y-center b)
         :r    (/ 6 zoom)
         :fill "none"
         :stroke pink-handle
         :stroke-width (/ 1.5 zoom)
         :style {:vectorEffect "non-scaling-stroke"
                 :pointerEvents "none"}}])
     ;; Pink gap handles: draggable-looking dots at each inter-item gap.
     ;; Pointer-down reuses the existing `distribute-objects` event to
     ;; re-even-out spacing on that axis. Pointer events are on these
     ;; elements only.
     (for [h gaps]
       [:circle.smart-selection-gap
        {:key  (dm/str "ss-gap-"
                       (name (:axis h)) "-"
                       (:cx h) "-" (:cy h))
         :cx   (:cx h)
         :cy   (:cy h)
         :r    (/ 4 zoom)
         :fill pink-handle
         :style {:vectorEffect "non-scaling-stroke"
                 :pointerEvents "all"}
         :on-pointer-down (if (= (:axis h) :horizontal) on-h-spacing on-v-spacing)}])]))

(mf/defc smart-selection-layer*
  "Renders the Smart Selection overlay when (and only when) the current
  selection forms a uniform layout. Always returns nil when detection
  fails, so the viewport is byte-identical to before when inactive."
  {::mf/wrap-props false}
  [{:keys [shapes zoom]}]
  (let [layout (mf/use-memo (mf/deps shapes) #(ssm/detect-smart-selection shapes))]
    (when (some? layout)
      [:> smart-selection-overlay* {:layout layout :zoom zoom}])))