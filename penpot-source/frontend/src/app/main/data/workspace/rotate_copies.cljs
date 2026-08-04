;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.rotate-copies
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.math :as mth]
   [app.common.types.modifiers :as ctm]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; Figma-parity "Rotate copies" (ALL_APPS_PARITY P2.32).
;;
;; Duplicates the current selection (count - 1) times and rotates each
;; batch around a pivot so the copies fan out evenly: batch i (1..count-1)
;; is rotated by (360/count)*i degrees. The original selection stays at
;; 0deg. The pivot defaults to the selection's bounding-rect center but the
;; caller (the modal) can pass an explicit `center` (a gpt/point).
;;
;; Why this shape:
;;   * Reuses the standard Duplicate primitive `cll/generate-duplicate-changes`
;;     (+ `...-update-indices`) so component / variant / flow / library
;;     machinery is preserved — no bespoke id / parent / index logic.
;;   * One batch per copy. `objects` is evolved across batches (the original
;;     objects plus the prior batches' freshly-added shapes) so each batch's
;;     index-fixing sees the copies already inserted and the z-order is
;;     correct rather than every copy collapsing onto the original's slot.
;;   * The rotation is baked into each new shape's geometry in the SAME
;;     changeset via `pcb/update-shapes` + `gsh/transform-shape` with
;;     `ctm/rotation-modifiers` — the documented bake pattern (see
;;     data/workspace/texts.cljs). `pcb/with-objects` is bound to the
;;     evolved objects so `update-shapes` can resolve the just-added ids.
;;   * All batch changesets are concatenated into ONE `dch/commit-changes`
;;     wrapped in `dwu/start/commit-undo-transaction` → a single undo step.
;;   * New ids per batch are recovered from the batch's own `:redo-changes`
;;     (`:add-obj` entries whose `:old-id` is one of the selected ids), the
;;     same technique `data/workspace/selection.cljs ::duplicate-shapes`
;;     uses. After commit, all the new copies are selected.
(defn rotate-copies
  [{:keys [count center] :as params}]
  (ptk/reify ::rotate-copies
    ptk/WatchEvent
    (watch [it state _]
      (let [page         (dsh/lookup-page state)
            objects      (:objects page)
            ids          (dsh/lookup-selected state)]
        ;; No selection -> nothing to do. Guard before any geometry work
        ;; so `shapes->rect` never sees an empty seq.
        (if (or (nil? ids) (empty? ids))
          (rx/empty)
          (let [n            (max 2 (int count))
                file-id      (:current-file-id state)
                libraries    (dsh/lookup-libraries state)
                library-data (dsh/lookup-file-data state file-id)
                delta        (gpt/point 0 0)
                ;; Default pivot = selection bounding-rect center; fall
                ;; back to the origin if the rect is somehow nil.
                pivot        (or center
                                 (some-> (gsh/shapes->rect
                                          (keep #(get objects %) ids))
                                         grc/rect->center)
                                 (gpt/point 0 0))
                step-deg     (/ 360.0 n)

                batches
                (reduce
                 (fn [{:keys [objects changes all-new-ids]} i]
                   (let [angle-rad   (mth/radians (* step-deg i))
                         batch-ch    (-> (pcb/empty-changes it)
                                         (cll/generate-duplicate-changes
                                          objects page ids delta
                                          libraries library-data file-id {})
                                         (cll/generate-duplicate-changes-update-indices
                                          objects ids))
                         add-entries (filter #(= (:type %) :add-obj)
                                             (:redo-changes batch-ch))
                         new-objs    (into {}
                                          (keep #(let [o (:obj %)]
                                                   (when (some? o) [(:id o) o])))
                                          add-entries)
                         new-ids     (into #{} (keys new-objs))
                         objects'    (into objects new-objs)
                         ;; Bind the evolved objects so `update-shapes` can
                         ;; resolve the just-added shape ids, then bake the
                         ;; rotation into each new shape's geometry.
                         batch-ch    (-> batch-ch
                                         (pcb/with-objects objects')
                                         (pcb/update-shapes
                                          new-ids
                                          (fn [shape]
                                            (gsh/transform-shape
                                             shape
                                             (ctm/rotation-modifiers
                                              shape pivot angle-rad)))))]
                     {:objects objects'
                      :changes (pcb/concat-changes changes batch-ch)
                      :all-new-ids (into all-new-ids new-ids)}))
                 {:objects objects
                  :changes (pcb/empty-changes it)
                  :all-new-ids #{}}
                 (range 1 n))

                changes     (:changes batches)
                all-new-ids (:all-new-ids batches)
                undo-id     (js/Symbol)]
            (rx/concat
             (rx/of (dwu/start-undo-transaction undo-id)
                    (dch/commit-changes changes))
             (if (seq all-new-ids)
               (rx/of (dws/select-shapes (into (d/ordered-set) all-new-ids))
                      (dwu/commit-undo-transaction undo-id))
               (rx/of (dwu/commit-undo-transaction undo-id))))))))))