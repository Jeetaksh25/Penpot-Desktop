;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.repeat-grid
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.types.modifiers :as ctm]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; Figma/Lunacy-parity "Repeat Grid" (ALL_APPS_PARITY P1.31).
;;
;; Duplicates the current selection into a rows x cols grid. The original
;; selection stays put as cell (0, 0); every other cell (row, col) is a
;; duplicate of the original selection translated by
;; (col * gap-w, row * gap-h) relative to the original's top-left. `gap-w`
;; and `gap-h` are the cell-to-cell pitches (so the default of
;; width / height yields touching copies; larger values add spacing).
;;
;; Why this shape (mirrors `data/workspace/rotate_copies.cljs`):
;;   * Reuses the standard Duplicate primitive `cll/generate-duplicate-changes`
;;     (+ `...-update-indices`) so component / variant / flow / library
;;     machinery is preserved — no bespoke id / parent / index logic.
;;   * One batch per grid cell (skipping (0,0)). `objects` is evolved across
;;     batches (the original objects plus the prior batches' freshly-added
;;     shapes) so each batch's index-fixing sees the copies already inserted
;;     and the z-order is correct rather than every copy collapsing onto the
;;     original's slot.
;;   * The translation is baked into each new shape's geometry in the SAME
;;     changeset via `pcb/update-shapes` + `gsh/transform-shape` with
;;     `ctm/move-modifiers` — the documented bake pattern (see
;;     data/workspace/texts.cljs / rotate_copies.cljs). `pcb/with-objects`
;;     is bound to the evolved objects so `update-shapes` can resolve the
;;     just-added ids.
;;   * All batch changesets are concatenated into ONE `dch/commit-changes`
;;     wrapped in `dwu/start/commit-undo-transaction` → a single undo step.
;;   * New ids per batch are recovered from the batch's own `:redo-changes`
;;     (`:add-obj` entries), the same technique
;;     `data/workspace/selection.cljs ::duplicate-shapes` uses. After commit,
;;     all the new copies are selected (the original keeps its place).
(defn repeat-grid
  [{:keys [rows cols gap-w gap-h] :as params}]
  (ptk/reify ::repeat-grid
    ptk/WatchEvent
    (watch [it state _]
      (let [page    (dsh/lookup-page state)
            objects (:objects page)
            ids     (dsh/lookup-selected state)]
        ;; No selection -> nothing to do. Guard before any geometry work
        ;; so `shapes->rect` never sees an empty seq.
        (if (or (nil? ids) (empty? ids))
          (rx/empty)
          (let [shapes       (keep #(get objects %) ids)
                rect         (or (gsh/shapes->rect shapes)
                                 (grc/make-rect 0 0 0 0))
                rect-w       (:width  rect 0)
                rect-h       (:height rect 0)
                r            (max 1 (int rows))
                c            (max 1 (int cols))
                ;; Cell pitch = shape size + spacing. Default the pitch to
                ;; the selection's own size so a fresh modal (gap 0) yields
                ;; touching copies, matching Figma's initial Repeat Grid.
                pitch-w      (if (number? gap-w) (+ rect-w gap-w) rect-w)
                pitch-h      (if (number? gap-h) (+ rect-h gap-h) rect-h)
                file-id      (:current-file-id state)
                libraries    (dsh/lookup-libraries state)
                library-data (dsh/lookup-file-data state file-id)
                delta        (gpt/point 0 0)

                cells        (for [row (range r)
                                   col (range c)
                                   :when (not (and (zero? row) (zero? col)))]
                               [row col])

                batches
                (reduce
                 (fn [{:keys [objects changes all-new-ids]} [row col]]
                   (let [dx          (* col pitch-w)
                         dy          (* row pitch-h)
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
                         ;; translation into each new shape's geometry.
                         batch-ch    (-> batch-ch
                                         (pcb/with-objects objects')
                                         (pcb/update-shapes
                                          new-ids
                                          (fn [shape]
                                            (gsh/transform-shape
                                             shape
                                             (ctm/move-modifiers dx dy)))))]
                     {:objects objects'
                      :changes (pcb/concat-changes changes batch-ch)
                      :all-new-ids (into all-new-ids new-ids)}))
                 {:objects objects
                  :changes (pcb/empty-changes it)
                  :all-new-ids #{}}
                 cells)

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