;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.concentric-corners
  "Concentric (Auto) Corners — Sketch Athens 2025.1 parity (ALL_APPS_PARITY P2.13).

  A corner mode that auto-calculates a child shape's corner radius from its
  container's radius minus the inset, producing visually harmonious nested
  corners without manual math. When the user invokes the 'Auto' action on a
  selection, for each selected shape we:

    1. Walk up the parent tree (via `cfh/get-parent`) to the nearest ancestor
       that admits a border radius (`ctsr/has-radius?`).
    2. Read that ancestor's per-corner radii (:r1..:r4), defaulting to 0.
    3. Compute the per-corner inset from the shape's selrect vs the ancestor's
       selrect — for each corner the inset is the smaller of the two axis
       offsets (the axis-aligned distance from the ancestor's corner to the
       child's corner), which yields a tangent-concentric result.
    4. Bake `max(0, ancestor-radius - inset)` into the shape's :r1..:r4 through
       the standard `dwsh/update-shapes` primitive — one undo step for the
       whole selection. Shapes whose nearest radius-bearing ancestor is the
       root (no radius) are left untouched.

  The manual per-corner controls in `border_radius.cljs` remain fully
  functional; 'Auto' is an additive one-shot action that writes through the
  same `:r1..:r4` attrs the manual controls use."
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.math :as mth]
   [app.common.types.shape.radius :as ctsr]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.shapes :as dwsh]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; --- pure geometry ---------------------------------------------------------

(defn- corner-insets
  "Given a child shape's selrect and an ancestor shape's selrect, return a
  map of `{:r1 :r2 :r3 :r4}` holding the per-corner inset (axis-aligned
  distance from the ancestor's corner to the child's corner, taken as the
  smaller of the two axis offsets so the resulting arc stays tangent to both
  edges).

  Corner numbering follows Penpot's convention:
    :r1 = top-left, :r2 = top-right,
    :r3 = bottom-right, :r4 = bottom-left.

  Both selrects are maps with :x1 :y1 :x2 :y2 (left/top/right/bottom)."
  [child-rect ancestor-rect]
  (let [c-x1 (:x1 child-rect)  c-y1 (:y1 child-rect)
        c-x2 (:x2 child-rect)  c-y2 (:y2 child-rect)
        a-x1 (:x1 ancestor-rect) a-y1 (:y1 ancestor-rect)
        a-x2 (:x2 ancestor-rect) a-y2 (:y2 ancestor-rect)
        ;; Distances are non-negative: a child that overflows its ancestor
        ;; contributes a 0 inset on that edge (the corner is flush with or
        ;; beyond the ancestor's corner), so the radius is not inflated.
        dx-l (mth/max 0 (- c-x1 a-x1))
        dx-r (mth/max 0 (- a-x2 c-x2))
        dy-t (mth/max 0 (- c-y1 a-y1))
        dy-b (mth/max 0 (- a-y2 c-y2))]
    {:r1 (mth/min dx-l dy-t)
     :r2 (mth/min dx-r dy-t)
     :r3 (mth/min dx-r dy-b)
     :r4 (mth/min dx-l dy-b)}))

(defn- normalize-radius
  "Radius attrs may be nil on shapes that support radius but never had it set;
  treat nil as 0."
  [r]
  (or r 0))

(defn compute-auto-radii
  "Pure: given the `child` shape and its nearest radius-bearing `ancestor`
  shape, return a map of `{:r1 :r2 :r3 :r4}` with the concentric corner
  radii `max(0, ancestor-radius - inset)` per corner. Returns nil if either
  shape lacks a usable selrect."
  [child ancestor]
  (let [child-rect    (:selrect child)
        ancestor-rect (:selrect ancestor)]
    (when (and (some? child-rect) (some? ancestor-rect))
      (let [insets (corner-insets child-rect ancestor-rect)]
        {:r1 (mth/max 0 (- (normalize-radius (:r1 ancestor)) (:r1 insets)))
         :r2 (mth/max 0 (- (normalize-radius (:r2 ancestor)) (:r2 insets)))
         :r3 (mth/max 0 (- (normalize-radius (:r3 ancestor)) (:r3 insets)))
         :r4 (mth/max 0 (- (normalize-radius (:r4 ancestor)) (:r4 insets)))}))))

(defn- nearest-radius-ancestor
  "Walk up `objects` from `shape-id` to the first ancestor that admits a
  border radius (`ctsr/has-radius?`). Returns the ancestor shape map or nil
  if none is found before the root."
  [objects shape-id]
  (loop [id (cfh/get-parent-id objects shape-id)]
    (when (some? id)
      (let [ancestor (get objects id)]
        (cond
          (nil? ancestor) nil
          (ctsr/has-radius? ancestor) ancestor
          :else (recur (cfh/get-parent-id objects id)))))))

;; --- event -----------------------------------------------------------------

(defn apply-auto-radius
  "PTK event: for every id in `ids`, look up the shape and its nearest
  radius-bearing ancestor, compute the concentric radii, and bake them into
  :r1..:r4 via `dwsh/update-shapes`. One undo step for the whole selection.
  Shapes with no radius-bearing ancestor (or no selrect) are left untouched."
  [ids]
  (ptk/reify ::apply-auto-radius
    ptk/WatchEvent
    (watch [it state _]
      (let [objects (dsh/lookup-page-objects state)
            ids     (into [] (filter some?) ids)]
        (if (empty? ids)
          (rx/empty)
          (let [event (dwsh/update-shapes
                       ids
                       (fn [shape]
                         (if-not (ctsr/has-radius? shape)
                           shape
                           (if-let [ancestor (nearest-radius-ancestor objects (:id shape))]
                             (if-let [radii (compute-auto-radii shape ancestor)]
                               (assoc shape
                                      :r1 (:r1 radii)
                                      :r2 (:r2 radii)
                                      :r3 (:r3 radii)
                                      :r4 (:r4 radii))
                               shape)
                           shape)))
                       {:reg-objects? true
                        :attrs [:r1 :r2 :r3 :r4]})]
            (rx/of event)))))))