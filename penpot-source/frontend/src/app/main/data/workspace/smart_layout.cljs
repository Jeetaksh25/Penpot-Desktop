;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.smart-layout
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.shapes :as dwsh]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; P1.03 "Smart Layout" (ALL_APPS_PARITY).
;;
;; A one-click affordance on a selected flex container that applies a
;; deterministic, heuristic layout pass — no LLM round-trip, no agent
;; loop. Two modes, both committed as a single undo step via
;; `dwsh/update-shapes` (which builds its own changes + saves undo):
;;
;;   :distribute — "Distribute evenly". Computes the even gap that
;;     spreads the container's direct children across its main axis so
;;     every inter-child gap is identical, and writes it back as
;;     `:layout-gap` (both row/column gaps set to the same value so the
;;     result is direction-agnostic) plus `:layout-justify-content`
;;     `:start` so the computed gap is the *actual* spacing (space-*
;;     justify values would otherwise override the gap). Free space is
;;     `(container main size - main padding) - sum(children main sizes)`;
;;     the even gap is `max(0, free / (n-1))`. With < 2 children there
;;     is nothing to distribute → the container is left untouched.
;;
;;   :fit — "Fit to content" / pack-to-fit. Resizes the container to
;;     the bounding rect of its direct children expanded by the
;;     container's own padding (`p1`/`p2`/`p3`/`p4`). Children keep
;;     their absolute coordinates; only the container's
;;     `:x`/`:y`/`:width`/`:height` move. With no children the
;;     container is left untouched.
;;
;; Why `dwsh/update-shapes` rather than a hand-rolled pcb + dch/commit
;; changes: the two operations here are pure attribute writes on the
;; selected container(s) — `:layout-gap`/`:layout-justify-content` for
;; distribute, `:x`/`:y`/`:width`/`:height` for fit. `update-shapes`
;; builds the changes, registers the layout re-flow (`update-layout?`
;; defaults true) and saves a single undo step itself, which is exactly
;; the granularity we want. Iterating over every selected id in one
;; `update-shapes` call keeps all containers in one undo entry.
;;
;; Geometry notes:
;;   * Container main axis is width for `:row`/`:row-reverse` and height
;;     for `:column`/`:column-reverse` (read from `:layout-flex-dir`).
;;   * Child main-axis sizes use the child's `:width` / `:height`
;;     directly (Penpot stores absolute pixel sizes there regardless of
;;     flex sizing mode; for fill children the rendered size is what we
;;     want for distribution math).
;;   * Padding comes from `ctl/h-padding` / `ctl/v-padding` which already
;;     handle the `:simple` vs `:multiple` padding-type distinction.

(defn- main-axis-size
  "Inner size along the flex main axis (container size minus main padding)."
  [container]
  (let [dir (or (:layout-flex-dir container) :row)]
    (if (contains? #{:row :row-reverse} dir)
      (- (mth/finite (:width container) 0) (ctl/h-padding container))
      (- (mth/finite (:height container) 0) (ctl/v-padding container)))))

(defn- child-main-size
  "Child size along the container's main axis."
  [container child]
  (let [dir (or (:layout-flex-dir container) :row)]
    (if (contains? #{:row :row-reverse} dir)
      (mth/finite (:width child) 0)
      (mth/finite (:height child) 0))))

(defn- compute-distribute-fn
  "Returns the update-fn applied to a container to distribute its
  children evenly, or nil if there is nothing to distribute."
  [objects container]
  (let [children (cfh/get-immediate-children objects (:id container))
        n        (count children)]
    (when (>= n 2)
      (let [main-inner (main-axis-size container)
            total      (transduce (keep #(child-main-size container %))
                                  + 0 children)
            free       (- main-inner total)
            gap        (max 0 (if (> n 1) (/ free (dec n)) 0))
            gap        (mth/finite gap 0)]
        (fn [shape]
          (-> shape
              (assoc :layout-gap {:row-gap gap :column-gap gap})
              (assoc :layout-justify-content :start)))))))

(defn- compute-fit-fn
  "Returns the update-fn applied to a container to resize it to the
  bounding box of its children plus the container's own padding, or nil
  if the container has no children."
  [objects container]
  (let [children (cfh/get-immediate-children objects (:id container))]
    (when (seq children)
      (let [rect       (gsh/shapes->rect children)
            [pad-top pad-right pad-bottom pad-left] (ctl/paddings container)]
        (when (some? rect)
          (let [x      (- (mth/finite (:x rect) 0) (mth/finite pad-left 0))
                y      (- (mth/finite (:y rect) 0) (mth/finite pad-top 0))
                width  (+ (mth/finite (:width rect) 0)
                          (mth/finite pad-left 0)
                          (mth/finite pad-right 0))
                height (+ (mth/finite (:height rect) 0)
                          (mth/finite pad-top 0)
                          (mth/finite pad-bottom 0))]
            (fn [shape]
              (-> shape
                  (assoc :x x :y y :width width :height height)))))))))

(defn- valid-flex-container?
  "True when shape is a flex layout container (`:layout` `:flex`). Smart
  layout only targets flex containers — grid has its own track model
  and a free-form gap would be meaningless."
  [shape]
  (and (some? shape)
       (= :flex (:layout shape))))

(defn smart-layout
  "One-click Smart Layout heuristic. `ids` = the selected container ids
  to apply to; `mode` = `:distribute` or `:fit`. Non-flex ids and ids
  with no children are skipped silently. One `dwsh/update-shapes` call
  → one undo step for the whole batch."
  [{:keys [ids mode] :or {mode :distribute}}]
  (ptk/reify ::smart-layout
    ptk/WatchEvent
    (watch [it state _]
      (let [objects (dsh/lookup-page-objects state)
            picked  (into []
                          (keep (fn [id]
                                  (let [shape (get objects id)]
                                    (when (valid-flex-container? shape)
                                      (let [f (case mode
                                                :distribute (compute-distribute-fn objects shape)
                                                :fit        (compute-fit-fn objects shape)
                                                nil)]
                                        (when (some? f) [id f]))))))
                          ids)]
        (if (empty? picked)
          (rx/empty)
          (let [update-ids (mapv first picked)
                fns        (into {} picked)]
            (rx/of (dwsh/update-shapes
                    update-ids
                    (fn [shape]
                      (let [f (get fns (:id shape))]
                        (if (some? f) (f shape) shape)))))))))))